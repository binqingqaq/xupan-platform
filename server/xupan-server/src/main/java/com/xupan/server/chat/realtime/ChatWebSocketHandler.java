package com.xupan.server.chat.realtime;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.security.AuthenticatedUserDetailsService;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.chat.web.ChatMessageResponse;
import com.xupan.server.chat.web.ChatAvatarResolver;
import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the authenticated chat protocol and delegates business rules to chat services. */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatWebSocketProperties properties;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;
    private final ChatRealtimeSyncService syncService;
    private final AuthenticatedUserDetailsService userDetailsService;
    private final ChatConnectionAccessService accessService;
    private final ChatAvatarResolver avatarResolver;
    private final Map<String, MessageRateWindow> messageRateWindows = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatConnectionRegistry connectionRegistry,
                                ChatWebSocketProperties properties,
                                 ObjectMapper objectMapper,
                                 ChatMessageService chatMessageService,
                                ChatRealtimeSyncService syncService,
                                AuthenticatedUserDetailsService userDetailsService,
                                ChatConnectionAccessService accessService,
                                ChatAvatarResolver avatarResolver) {
        this.connectionRegistry = connectionRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
        this.syncService = syncService;
        this.userDetailsService = userDetailsService;
        this.accessService = accessService;
        this.avatarResolver = avatarResolver;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AuthenticatedUser user = attribute(session, ChatWebSocketHandshakeInterceptor.USER_ATTRIBUTE,
                AuthenticatedUser.class);
        String sessionId = attribute(session, ChatWebSocketHandshakeInterceptor.SESSION_ID_ATTRIBUTE, String.class);
        String roomCode = attribute(session, ChatWebSocketHandshakeInterceptor.ROOM_CODE_ATTRIBUTE, String.class);
        ChatConnection connection = new ChatConnection(UUID.randomUUID().toString(), user.getUserId(),
                sessionId, roomCode, session, Instant.now());
        try {
            connectionRegistry.register(connection);
            session.getAttributes().put(ChatWebSocketHandshakeInterceptor.CONNECTION_ATTRIBUTE, connection);
            send(connection, ChatProtocol.connected(connection.connectionId(), roomCode, Instant.now()));
        } catch (ChatConnectionRegistry.ConnectionLimitException exception) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ChatConnection connection = connection(session);
        if (connection == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        ChatConnectionAccessService.AccessCheck access = accessService.check(connection, Instant.now());
        if (!access.allowed()) {
            remove(session);
            connection.close(access.closeStatus());
            return;
        }
        try {
            ChatProtocol.ClientEvent event = ChatProtocol.parseClientEvent(objectMapper, message.getPayload(),
                    properties.getMaxMessageBytes());
            ChatProtocol.ClientEventType eventType = ChatProtocol.ClientEventType.fromWire(event.type());
            if (eventType == ChatProtocol.ClientEventType.PING) {
                connection.markPong(Instant.now());
                send(connection, ChatProtocol.pong(event.nonce(), Instant.now()));
            } else if (eventType == ChatProtocol.ClientEventType.SUBSCRIBE) {
                handleSubscribe(connection, event);
            } else {
                requireSubscribed(connection);
                if (eventType == ChatProtocol.ClientEventType.MESSAGE_SEND) {
                    handleMessageSend(connection, event);
                } else if (eventType == ChatProtocol.ClientEventType.CURSOR_ACK) {
                    handleCursorAck(connection, event);
                } else {
                    send(connection, ChatProtocol.error(ChatProtocol.ErrorCode.UNSUPPORTED_EVENT,
                            "不支持的聊天室事件"));
                }
            }
        } catch (ChatProtocol.ProtocolException exception) {
            send(connection, ChatProtocol.error(exception.code(), exception.getMessage()));
        } catch (BusinessException exception) {
            String clientMessageId = null;
            try {
                ChatProtocol.ClientEvent event = ChatProtocol.parseClientEvent(objectMapper, message.getPayload(),
                        properties.getMaxMessageBytes());
                clientMessageId = event.clientMessageId();
            } catch (ChatProtocol.ProtocolException ignored) {
                // The original frame is invalid; there is no trusted client message id to echo.
            }
            send(connection, ChatProtocol.error(exception.code(), exception.publicMessage(), clientMessageId));
        } catch (RuntimeException exception) {
            log.warn("聊天室 WebSocket 业务处理失败 connectionId={} roomCode={}",
                    connection.connectionId(), connection.roomCode(), exception);
            send(connection, ChatProtocol.error(ChatProtocol.ErrorCode.MESSAGE_REJECTED,
                    "聊天室操作暂时无法完成"));
        }
    }

    private void handleSubscribe(ChatConnection connection, ChatProtocol.ClientEvent event) {
        if (!connection.roomCode().equals(event.roomCode())) {
            throw BusinessException.forbidden("CHAT_ROOM_FORBIDDEN", "不能订阅当前连接之外的聊天室");
        }
        ChatRealtimeSyncService.SyncPlan plan = syncService.prepare(connection.userId(), connection.roomCode(),
                event.afterSequence() == null ? 0L : event.afterSequence(), Instant.now());
        connection.markSubscribed();
        send(connection, ChatProtocol.syncRequired(plan.afterSequence()));
        send(connection, ChatProtocol.syncComplete(plan.afterSequence(), plan.latestSequence()));
    }

    private void handleMessageSend(ChatConnection connection, ChatProtocol.ClientEvent event) {
        requireMessageSendPermission(connection);
        if (!allowMessage(connection, Instant.now())) {
            send(connection, ChatProtocol.error(ChatProtocol.ErrorCode.MESSAGE_RATE_LIMITED,
                    "消息发送过于频繁，请稍后再试", event.clientMessageId()));
            return;
        }
        ChatMessageService.ChatMessageSendOutcome outcome = chatMessageService.sendUserMessageWithOutcome(
                connection.userId(), connection.roomCode(), event.clientMessageId(), event.content(), Instant.now());
        ChatMessageResponse response = avatarResolver.toResponse(outcome.message());
        send(connection, ChatProtocol.messageAck(event.clientMessageId(), response, outcome.deduplicated()));
    }

    private void requireMessageSendPermission(ChatConnection connection) {
        AuthenticatedUser currentUser;
        try {
            currentUser = userDetailsService.loadUserById(connection.userId());
        } catch (RuntimeException exception) {
            throw BusinessException.forbidden("CHAT_PERMISSION_DENIED", "当前账号没有发送聊天室消息的权限");
        }
        boolean allowed = currentUser.isEnabled()
                && currentUser.isAccountNonLocked()
                && currentUser.getAuthorities().stream()
                .anyMatch(authority -> "PERM_CHAT_MESSAGE_SEND".equals(authority.getAuthority()));
        if (!allowed) {
            throw BusinessException.forbidden("CHAT_PERMISSION_DENIED", "当前账号没有发送聊天室消息的权限");
        }
    }

    private void handleCursorAck(ChatConnection connection, ChatProtocol.ClientEvent event) {
        chatMessageService.saveReadCursor(connection.userId(), connection.roomCode(), event.sequence(), Instant.now());
        send(connection, ChatProtocol.cursorAck(event.sequence()));
    }

    private void requireSubscribed(ChatConnection connection) {
        if (!connection.isSubscribed()) {
            throw BusinessException.forbidden("CHAT_SUBSCRIPTION_REQUIRED", "请先订阅当前聊天室");
        }
    }

    private boolean allowMessage(ChatConnection connection, Instant now) {
        return messageRateWindows.computeIfAbsent(connection.connectionId(), ignored -> new MessageRateWindow())
                .tryAcquire(now, properties.getMaxMessagesPerWindow(), properties.getMessageRateWindow());
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        ChatConnection connection = connection(session);
        if (connection != null) {
            connection.markPong(Instant.now());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        remove(session);
        log.debug("聊天室 WebSocket 传输异常 sessionId={}", session.getId(), exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session);
    }

    private void send(ChatConnection connection, ChatProtocol.ServerEvent event) {
        String payload = ChatProtocol.encode(objectMapper, event, properties.getMaxOutboundMessageBytes());
        if (!connection.sendText(payload, properties.getMaxPendingMessages())) {
            remove(connection.session());
            connection.close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void remove(WebSocketSession session) {
        Object value = session.getAttributes().remove(ChatWebSocketHandshakeInterceptor.CONNECTION_ATTRIBUTE);
        if (value instanceof ChatConnection connection) {
            messageRateWindows.remove(connection.connectionId());
            connectionRegistry.remove(connection.connectionId());
        }
    }

    private ChatConnection connection(WebSocketSession session) {
        Object value = session.getAttributes().get(ChatWebSocketHandshakeInterceptor.CONNECTION_ATTRIBUTE);
        return value instanceof ChatConnection connection ? connection : null;
    }

    private static <T> T attribute(WebSocketSession session, String key, Class<T> type) {
        Object value = session.getAttributes().get(key);
        if (!type.isInstance(value)) {
            throw new IllegalStateException("WebSocket 握手上下文缺失");
        }
        return type.cast(value);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // The connection is already unusable.
        }
    }

    private static final class MessageRateWindow {

        private Instant startedAt;
        private int count;

        private synchronized boolean tryAcquire(Instant now, int maxMessages, java.time.Duration window) {
            if (startedAt == null || !now.isBefore(startedAt.plus(window))) {
                startedAt = now;
                count = 0;
            }
            if (count >= maxMessages) {
                return false;
            }
            count++;
            return true;
        }
    }
}
