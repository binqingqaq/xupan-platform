package com.xupan.server.chat.realtime;

import com.xupan.server.chat.domain.ChatMessageVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ChatConnectionRegistryTest {

    @Test
    void evictsOldestConnectionForUserAndKeepsIndexesConsistent() throws Exception {
        ChatWebSocketProperties properties = properties(2, 10);
        ChatConnectionAccessService accessService = allowedAccessService();
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties, accessService);
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
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties, allowedAccessService());
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

    @Test
    void machineMessageBroadcastDoesNotReachAnotherAgentsConnections() throws Exception {
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties(3, 10), allowedAccessService());
        WebSocketSession firstSession = openSession();
        WebSocketSession sameAgentSession = openSession();
        WebSocketSession otherAgentSession = openSession();
        ChatConnection first = new ChatConnection("machine-1", 7L, "session-7", "main", 101L,
                firstSession, Instant.parse("2026-09-11T00:00:00Z"));
        ChatConnection sameAgent = new ChatConnection("machine-1-peer", 8L, "session-8", "main", 101L,
                sameAgentSession, Instant.parse("2026-09-11T00:00:01Z"));
        ChatConnection otherAgent = new ChatConnection("machine-2", 9L, "session-9", "main", 202L,
                otherAgentSession, Instant.parse("2026-09-11T00:00:02Z"));
        first.markSubscribed();
        sameAgent.markSubscribed();
        otherAgent.markSubscribed();
        registry.register(first);
        registry.register(sameAgent);
        registry.register(otherAgent);

        assertThat(registry.broadcast("main", ChatMessageVisibility.MACHINE, 101L, "private"))
                .isEqualTo(2);
        verify(firstSession).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(sameAgentSession).sendMessage(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(otherAgentSession, org.mockito.Mockito.never())
                .sendMessage(org.mockito.ArgumentMatchers.any());

        assertThat(registry.broadcast("main", ChatMessageVisibility.PUBLIC, null, "draw"))
                .isEqualTo(3);
    }

    @Test
    void machinePageRefreshTargetsOnlyTheMachineAcrossRoomsAndSkipsObservers() throws Exception {
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties(5, 10), allowedAccessService());
        WebSocketSession targetSession = openSession();
        WebSocketSession sameMachineOtherRoomSession = openSession();
        WebSocketSession otherMachineSession = openSession();
        WebSocketSession observerSession = openSession();
        ChatConnection target = new ChatConnection("target", 7L, "session-7", "room-a", 101L,
                targetSession, Instant.parse("2026-09-11T00:00:00Z"));
        ChatConnection sameMachineOtherRoom = new ChatConnection("same-machine", 8L, "session-8", "room-b", 101L,
                sameMachineOtherRoomSession, Instant.parse("2026-09-11T00:00:01Z"));
        ChatConnection otherMachine = new ChatConnection("other-machine", 9L, "session-9", "room-a", 202L,
                otherMachineSession, Instant.parse("2026-09-11T00:00:02Z"));
        ChatConnection observer = observerConnection("observer", "observer-session", observerSession,
                Instant.parse("2026-09-11T00:00:03Z"));
        target.markSubscribed();
        sameMachineOtherRoom.markSubscribed();
        otherMachine.markSubscribed();
        observer.markSubscribed();
        registry.register(target);
        registry.register(sameMachineOtherRoom);
        registry.register(otherMachine);
        registry.register(observer);

        assertThat(registry.broadcastToMachinePlayers(101L, "refresh")).isEqualTo(2);
        verify(targetSession).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(sameMachineOtherRoomSession).sendMessage(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(otherMachineSession, org.mockito.Mockito.never())
                .sendMessage(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(observerSession, org.mockito.Mockito.never())
                .sendMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removesConnectionBeforeBroadcastWhenCurrentAccessIsRevoked() throws Exception {
        ChatWebSocketProperties properties = properties(3, 10);
        ChatConnectionAccessService accessService = mock(ChatConnectionAccessService.class);
        when(accessService.check(any(ChatConnection.class), any(Instant.class)))
                .thenReturn(ChatConnectionAccessService.AccessCheck.forbidden());
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties, accessService);
        WebSocketSession session = openSession();
        ChatConnection connection = connection("revoked-1", session, Instant.parse("2026-09-11T00:00:00Z"));
        connection.markSubscribed();
        registry.register(connection);

        assertThat(registry.broadcast("main", "{}"))
                .isZero();
        assertThat(registry.size()).isZero();
        verify(session).close(org.mockito.ArgumentMatchers.argThat(status -> status.getCode() == 4403));
    }

    @Test
    void removesConnectionDuringHeartbeatWhenSessionIsRevoked() throws Exception {
        ChatWebSocketProperties properties = properties(3, 10);
        ChatConnectionAccessService accessService = mock(ChatConnectionAccessService.class);
        when(accessService.check(any(ChatConnection.class), any(Instant.class)))
                .thenReturn(ChatConnectionAccessService.AccessCheck.unauthenticated());
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties, accessService);
        WebSocketSession session = openSession();
        ChatConnection connection = connection("revoked-2", session, Instant.parse("2026-09-11T00:00:00Z"));
        connection.markSubscribed();
        registry.register(connection);

        assertThat(registry.heartbeat(Instant.parse("2026-09-11T00:00:01Z")))
                .isEqualTo(1);
        assertThat(registry.size()).isZero();
        verify(session).close(org.mockito.ArgumentMatchers.argThat(status -> status.getCode() == 4401));
    }

    @Test
    void observerConnectionsHaveTheirOwnPerBindingCapAndDoNotConsumeBotUserSlots() throws Exception {
        ChatWebSocketProperties properties = properties(1, 10);
        properties.setMaxObserverConnectionsPerBinding(2);
        ChatConnectionRegistry registry = new ChatConnectionRegistry(properties, allowedAccessService());
        WebSocketSession playerSession = openSession();
        WebSocketSession observerSession1 = openSession();
        WebSocketSession observerSession2 = openSession();
        WebSocketSession otherBindingSession = openSession();
        WebSocketSession observerSession3 = openSession();
        ChatConnection player = new ChatConnection("player-1", 7L, "player-session", "main",
                101L, playerSession, Instant.parse("2026-11-01T00:00:00Z"));
        ChatConnection observer1 = observerConnection("observer-1", "observer-session-1", observerSession1,
                Instant.parse("2026-11-01T00:00:01Z"));
        ChatConnection observer2 = observerConnection("observer-2", "observer-session-2", observerSession2,
                Instant.parse("2026-11-01T00:00:02Z"));
        ChatConnection otherBindingObserver = observerConnection("observer-other-binding", "observer-session-other",
                "ZyXwVu9876543210", otherBindingSession, Instant.parse("2026-11-01T00:00:03Z"));
        ChatConnection observer3 = observerConnection("observer-3", "observer-session-3", observerSession3,
                Instant.parse("2026-11-01T00:00:04Z"));

        registry.register(player);
        registry.register(observer1);
        registry.register(observer2);
        registry.register(otherBindingObserver);
        registry.register(new ChatConnection("player-2", 7L, "player-session-2", "main",
                101L, openSession(), Instant.parse("2026-11-01T00:00:05Z")));

        verify(playerSession).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        assertThat(observer1.isOpen()).isTrue();
        assertThat(observer2.isOpen()).isTrue();
        assertThat(registry.userConnectionCount(7L)).isEqualTo(4);
        assertThat(registry.normalUserConnectionCount(7L)).isEqualTo(1);
        assertThat(registry.observerConnectionCount("AbCdEf0123456789")).isEqualTo(2);
        assertThat(registry.observerConnectionCount("ZyXwVu9876543210")).isEqualTo(1);

        assertThatThrownBy(() -> registry.register(observer3))
                .isInstanceOf(ChatConnectionRegistry.ConnectionLimitException.class)
                .satisfies(exception -> assertThat(
                        ((ChatConnectionRegistry.ConnectionLimitException) exception).closeStatus().getCode())
                        .isEqualTo(4429));
        assertThat(observer1.isOpen()).isTrue();
        assertThat(observer2.isOpen()).isTrue();
        assertThat(registry.size()).isEqualTo(4);
        assertThat(registry.normalUserConnectionCount(7L)).isEqualTo(1);
    }

    private static ChatConnectionAccessService allowedAccessService() {
        ChatConnectionAccessService accessService = mock(ChatConnectionAccessService.class);
        when(accessService.check(any(ChatConnection.class), any(Instant.class)))
                .thenReturn(ChatConnectionAccessService.AccessCheck.allowed(null));
        return accessService;
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

    private static ChatConnection observerConnection(String id, String sessionId,
                                                     WebSocketSession session, Instant connectedAt) {
        return observerConnection(id, sessionId, "AbCdEf0123456789", session, connectedAt);
    }

    private static ChatConnection observerConnection(String id, String sessionId, String publicId,
                                                     WebSocketSession session, Instant connectedAt) {
        return new ChatConnection(id, 7L, sessionId, "main", 101L, publicId,
                session, connectedAt);
    }
}
