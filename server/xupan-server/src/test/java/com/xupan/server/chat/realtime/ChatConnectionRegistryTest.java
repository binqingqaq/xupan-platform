package com.xupan.server.chat.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatConnectionRegistryTest {

    @Test
    void evictsOldestConnectionForUserAndKeepsIndexesConsistent() throws Exception {
        ChatWebSocketProperties properties = properties(2, 10);
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties);
        WebSocketSession firstSession = openSession();
        WebSocketSession secondSession = openSession();
        WebSocketSession thirdSession = openSession();
        ChatConnection first = connection("c1", firstSession, Instant.parse("2026-09-11T00:00:00Z"));
        ChatConnection second = connection("c2", secondSession, Instant.parse("2026-09-11T00:00:01Z"));
        ChatConnection third = connection("c3", thirdSession, Instant.parse("2026-09-11T00:00:02Z"));

        registry.register(first);
        registry.register(second);
        registry.register(third);

        verify(firstSession).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.userConnectionCount(7L)).isEqualTo(2);
        assertThat(registry.roomConnectionCount("main")).isEqualTo(2);
        assertThat(registry.remove("c2")).isSameAs(second);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void broadcastsOnlyToConnectionsInTheRequestedRoom() throws Exception {
        ChatWebSocketProperties properties = properties(3, 10);
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties);
        WebSocketSession mainSession = openSession();
        WebSocketSession otherSession = openSession();
        ChatConnection main = connection("main-1", mainSession, Instant.parse("2026-09-11T00:00:00Z"));
        ChatConnection other = new ChatConnection("other-1", 8L, "session-8", "other", otherSession,
                Instant.parse("2026-09-11T00:00:00Z"));
        main.markSubscribed();
        other.markSubscribed();
        registry.register(main);
        registry.register(other);

        assertThat(registry.broadcast("main", "{\"type\":\"message.created\"}")).isEqualTo(1);
        verify(mainSession).sendMessage(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(otherSession, org.mockito.Mockito.never())
                .sendMessage(org.mockito.ArgumentMatchers.any());
    }

    private static ChatWebSocketProperties properties(int perUser, int total) {
        ChatWebSocketProperties properties = new ChatWebSocketProperties();
        properties.setMaxConnectionsPerUser(perUser);
        properties.setMaxConnections(total);
        properties.setMaxPendingMessages(2);
        return properties;
    }

    private static WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static ChatConnection connection(String id, WebSocketSession session, Instant connectedAt) {
        return new ChatConnection(id, 7L, "session-7", "main", session, connectedAt);
    }
}
