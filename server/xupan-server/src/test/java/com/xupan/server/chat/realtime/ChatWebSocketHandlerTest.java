package com.xupan.server.chat.realtime;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.security.AuthenticatedUserDetailsService;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessageStatus;
import com.xupan.server.chat.domain.ChatMessageType;
import com.xupan.server.chat.domain.ChatSenderType;
import com.xupan.server.chat.service.ChatMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatMessageService messageService = mock(ChatMessageService.class);
    private final ChatRealtimeSyncService syncService = mock(ChatRealtimeSyncService.class);
    private final AuthenticatedUserDetailsService userDetailsService = mock(AuthenticatedUserDetailsService.class);
    private final ChatConnectionAccessService accessService = mock(ChatConnectionAccessService.class);
    private final ChatConnectionRegistry registry = new ChatConnectionRegistry(properties(), accessService);
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(
            registry, properties(), objectMapper, messageService, syncService, userDetailsService, accessService);
    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("ws-1");
        Map<String, Object> attributes = new HashMap<>();
        AuthenticatedUser user = mock(AuthenticatedUser.class);
        when(user.getUserId()).thenReturn(7L);
        when(user.isEnabled()).thenReturn(true);
        when(user.isAccountNonLocked()).thenReturn(true);
        org.mockito.Mockito.doReturn(java.util.List.of(
                new SimpleGrantedAuthority("PERM_CHAT_MESSAGE_SEND"))).when(user).getAuthorities();
        when(userDetailsService.loadUserById(7L)).thenReturn(user);
        when(accessService.check(any(ChatConnection.class), any(Instant.class)))
                .thenReturn(ChatConnectionAccessService.AccessCheck.allowed(user));
        attributes.put(ChatWebSocketHandshakeInterceptor.USER_ATTRIBUTE, user);
        attributes.put(ChatWebSocketHandshakeInterceptor.SESSION_ID_ATTRIBUTE, "session-7");
        attributes.put(ChatWebSocketHandshakeInterceptor.ROOM_CODE_ATTRIBUTE, "main");
        when(session.getAttributes()).thenReturn(attributes);
        handler.afterConnectionEstablished(session);
    }

    @Test
    void rejectsBusinessMessageBeforeSubscription() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"message.send\",\"clientMessageId\":\"m-1\",\"content\":\"hello\"}"));

        verify(messageService, org.mockito.Mockito.never())
                .sendUserMessageWithOutcome(any(Long.class), any(String.class), any(String.class), any(String.class), any(Instant.class));
        assertThat(sentPayloads()).anyMatch(value -> value.contains("CHAT_SUBSCRIPTION_REQUIRED"));
    }

    @Test
    void subscribesAndSendsPersistedMessageAck() throws Exception {
        when(syncService.prepare(eq(7L), eq("main"), eq(3L), any(Instant.class)))
                .thenReturn(new ChatRealtimeSyncService.SyncPlan(3L, 5L));
        ChatMessage message = message(5L, "m-1", "hello");
        when(messageService.sendUserMessageWithOutcome(eq(7L), eq("main"), eq("m-1"), eq("hello"), any(Instant.class)))
                .thenReturn(new ChatMessageService.ChatMessageSendOutcome(message, false));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"subscribe\",\"roomCode\":\"main\",\"afterSequence\":3}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"message.send\",\"clientMessageId\":\"m-1\",\"content\":\"hello\"}"));

        verify(messageService).sendUserMessageWithOutcome(eq(7L), eq("main"), eq("m-1"), eq("hello"), any(Instant.class));
        assertThat(sentPayloads()).anyMatch(value -> value.contains("\"type\":\"sync.required\"")
                && value.contains("\"afterSequence\":3"));
        assertThat(sentPayloads()).anyMatch(value -> value.contains("\"type\":\"message.ack\"")
                && value.contains("\"clientMessageId\":\"m-1\"")
                && value.contains("\"sequenceNo\":5"));
    }

    @Test
    void persistsReadCursorAfterSubscription() throws Exception {
        when(syncService.prepare(eq(7L), eq("main"), eq(0L), any(Instant.class)))
                .thenReturn(new ChatRealtimeSyncService.SyncPlan(0L, 0L));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"subscribe\",\"roomCode\":\"main\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"cursor.ack\",\"sequence\":0}"));

        verify(messageService).saveReadCursor(eq(7L), eq("main"), eq(0L), any(Instant.class));
        assertThat(sentPayloads()).anyMatch(value -> value.contains("\"type\":\"cursor.ack\"")
                && value.contains("\"sequence\":0"));
    }

    @Test
    void rejectsMessageWithoutCurrentSendPermission() throws Exception {
        when(syncService.prepare(eq(7L), eq("main"), eq(0L), any(Instant.class)))
                .thenReturn(new ChatRealtimeSyncService.SyncPlan(0L, 0L));
        AuthenticatedUser denied = mock(AuthenticatedUser.class);
        when(denied.isEnabled()).thenReturn(true);
        when(denied.isAccountNonLocked()).thenReturn(true);
        when(denied.getAuthorities()).thenReturn(java.util.List.of());
        when(userDetailsService.loadUserById(7L)).thenReturn(denied);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"subscribe\",\"roomCode\":\"main\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"message.send\",\"clientMessageId\":\"m-2\",\"content\":\"hello\"}"));

        verify(messageService, org.mockito.Mockito.never())
                .sendUserMessageWithOutcome(any(Long.class), any(String.class), any(String.class), any(String.class), any(Instant.class));
        assertThat(sentPayloads()).anyMatch(value -> value.contains("CHAT_PERMISSION_DENIED"));
    }

    @Test
    void closesConnectionWhenSessionIsRevokedBeforeNextBusinessFrame() throws Exception {
        when(accessService.check(any(ChatConnection.class), any(Instant.class)))
                .thenReturn(ChatConnectionAccessService.AccessCheck.unauthenticated());

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\",\"nonce\":\"n-1\"}"));

        verify(session).close(org.mockito.ArgumentMatchers.argThat(status -> status.getCode() == 4401));
        verify(messageService, org.mockito.Mockito.never())
                .saveReadCursor(any(Long.class), any(String.class), any(Long.class), any(Instant.class));
    }

    private ChatMessage message(long sequence, String clientMessageId, String content) {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        return new ChatMessage(1L, 1L, "main", sequence, clientMessageId, null, null,
                ChatMessageType.USER_CHAT, ChatSenderType.USER, 7L, "用户甲", content, null,
                ChatMessageStatus.ACTIVE, now, now);
    }

    private String[] sentPayloads() throws Exception {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .map(invocation -> ((TextMessage) invocation.getArgument(0)).getPayload())
                .toArray(String[]::new);
    }

    private static ChatWebSocketProperties properties() {
        ChatWebSocketProperties properties = new ChatWebSocketProperties();
        properties.setMaxConnections(10);
        properties.setMaxConnectionsPerUser(3);
        properties.setMaxPendingMessages(10);
        return properties;
    }
}
