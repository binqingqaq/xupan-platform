package com.xupan.server.chat.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** One authenticated WebSocket connection and its bounded write lifecycle. */
public final class ChatConnection {

    private static final Logger log = LoggerFactory.getLogger(ChatConnection.class);

    private final String connectionId;
    private final long userId;
    private final String sessionId;
    private final String roomCode;
    private final WebSocketSession session;
    private final Instant connectedAt;
    private final AtomicInteger pendingMessages = new AtomicInteger();
    private final AtomicLong lastPongMillis;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final ReentrantLock sendLock = new ReentrantLock();

    public ChatConnection(String connectionId, long userId, String sessionId, String roomCode,
                          WebSocketSession session, Instant connectedAt) {
        this.connectionId = requireText(connectionId, "connectionId");
        this.userId = userId;
        this.sessionId = requireText(sessionId, "sessionId");
        this.roomCode = requireText(roomCode, "roomCode");
        this.session = Objects.requireNonNull(session, "session");
        this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
        this.lastPongMillis = new AtomicLong(connectedAt.toEpochMilli());
    }

    public String connectionId() {
        return connectionId;
    }

    public long userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String roomCode() {
        return roomCode;
    }

    public WebSocketSession session() {
        return session;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public int pendingMessages() {
        return pendingMessages.get();
    }

    public Instant lastPongAt() {
        return Instant.ofEpochMilli(lastPongMillis.get());
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    public boolean isReadyForBroadcast() {
        return isOpen() && subscribed.get();
    }

    public boolean isSubscribed() {
        return subscribed.get();
    }

    public void markSubscribed() {
        subscribed.set(true);
    }

    public void markPong(Instant at) {
        if (at != null) {
            lastPongMillis.set(at.toEpochMilli());
        }
    }

    public boolean heartbeatExpired(Instant now, Duration timeout) {
        return now != null && timeout != null && !now.isBefore(lastPongAt().plus(timeout));
    }

    public boolean sendText(String payload, int maxPendingMessages) {
        Objects.requireNonNull(payload, "payload");
        if (!reservePending(maxPendingMessages)) {
            return false;
        }
        sendLock.lock();
        try {
            if (!session.isOpen()) {
                return false;
            }
            session.sendMessage(new TextMessage(payload));
            return true;
        } catch (IOException exception) {
            log.debug("聊天室连接写出失败 connectionId={} roomCode={}", connectionId, roomCode, exception);
            return false;
        } finally {
            pendingMessages.decrementAndGet();
            sendLock.unlock();
        }
    }

    public boolean sendPing(int maxPendingMessages) {
        if (!reservePending(maxPendingMessages)) {
            return false;
        }
        sendLock.lock();
        try {
            if (!session.isOpen()) {
                return false;
            }
            session.sendMessage(new PingMessage());
            return true;
        } catch (IOException exception) {
            log.debug("聊天室心跳写出失败 connectionId={} roomCode={}", connectionId, roomCode, exception);
            return false;
        } finally {
            pendingMessages.decrementAndGet();
            sendLock.unlock();
        }
    }

    public void close(CloseStatus status) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException exception) {
            log.debug("聊天室连接关闭失败 connectionId={} roomCode={}", connectionId, roomCode, exception);
        }
    }

    private boolean reservePending(int maxPendingMessages) {
        if (maxPendingMessages < 1) {
            throw new IllegalArgumentException("maxPendingMessages 必须大于 0");
        }
        while (true) {
            int current = pendingMessages.get();
            if (current >= maxPendingMessages) {
                return false;
            }
            if (pendingMessages.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
