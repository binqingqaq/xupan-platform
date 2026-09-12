package com.xupan.server.chat.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-process index for one instance's authenticated chat connections. */
@Component
public class ChatConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatConnectionRegistry.class);

    private final ChatWebSocketProperties properties;
    private final Object lifecycleLock = new Object();
    private final Map<String, ChatConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roomConnections = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userConnections = new ConcurrentHashMap<>();

    public ChatConnectionRegistry(ChatWebSocketProperties properties) {
        this.properties = properties;
    }

    public void register(ChatConnection connection) {
        ChatConnection evicted = null;
        synchronized (lifecycleLock) {
            if (connections.size() >= properties.getMaxConnections()) {
                throw new ConnectionLimitException("聊天室当前连接数已达上限");
            }
            Set<String> userIds = userConnections.computeIfAbsent(connection.userId(), ignored ->
                    ConcurrentHashMap.newKeySet());
            if (userIds.size() >= properties.getMaxConnectionsPerUser()) {
                evicted = userIds.stream().map(connections::get).filter(value -> value != null)
                        .min(Comparator.comparing(ChatConnection::connectedAt)).orElse(null);
                if (evicted != null) {
                    removeInternal(evicted);
                }
            }
            connections.put(connection.connectionId(), connection);
            roomConnections.computeIfAbsent(connection.roomCode(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(connection.connectionId());
            userConnections.computeIfAbsent(connection.userId(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(connection.connectionId());
        }
        if (evicted != null) {
            log.info("聊天室连接达到单用户上限，关闭最旧连接 userId={} roomCode={} connectionId={}",
                    evicted.userId(), evicted.roomCode(), evicted.connectionId());
            evicted.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    public ChatConnection remove(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        synchronized (lifecycleLock) {
            ChatConnection connection = connections.get(connectionId);
            if (connection != null) {
                removeInternal(connection);
            }
            return connection;
        }
    }

    public int size() {
        return connections.size();
    }

    public int userConnectionCount(long userId) {
        Set<String> ids = userConnections.get(userId);
        return ids == null ? 0 : ids.size();
    }

    public int roomConnectionCount(String roomCode) {
        Set<String> ids = roomConnections.get(roomCode);
        return ids == null ? 0 : ids.size();
    }

    public int broadcast(String roomCode, String payload) {
        Set<String> ids = roomConnections.get(roomCode);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (String connectionId : List.copyOf(ids)) {
            ChatConnection connection = connections.get(connectionId);
            if (connection == null || !connection.isReadyForBroadcast()) {
                continue;
            }
            if (connection.sendText(payload, properties.getMaxPendingMessages())) {
                delivered++;
            } else {
                remove(connection.connectionId());
                connection.close(CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
        return delivered;
    }

    public int heartbeat(Instant now) {
        int closed = 0;
        for (ChatConnection connection : new ArrayList<>(connections.values())) {
            if (connection.heartbeatExpired(now, properties.getHeartbeatTimeout())
                    || !connection.sendPing(properties.getMaxPendingMessages())) {
                if (remove(connection.connectionId()) != null) {
                    closed++;
                }
                connection.close(CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
        return closed;
    }

    public void closeAll() {
        for (ChatConnection connection : new ArrayList<>(connections.values())) {
            remove(connection.connectionId());
            connection.close(CloseStatus.GOING_AWAY);
        }
    }

    private void removeInternal(ChatConnection connection) {
        connections.remove(connection.connectionId(), connection);
        removeIndex(roomConnections, connection.roomCode(), connection.connectionId());
        removeIndex(userConnections, connection.userId(), connection.connectionId());
    }

    private static <K> void removeIndex(Map<K, Set<String>> index, K key, String connectionId) {
        Set<String> ids = index.get(key);
        if (ids != null) {
            ids.remove(connectionId);
            if (ids.isEmpty()) {
                index.remove(key, ids);
            }
        }
    }

    public static final class ConnectionLimitException extends RuntimeException {
        public ConnectionLimitException(String message) {
            super(message);
        }
    }
}
