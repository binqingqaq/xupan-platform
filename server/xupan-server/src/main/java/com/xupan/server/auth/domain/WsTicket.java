package com.xupan.server.auth.domain;

import java.time.Instant;
import java.util.Objects;

/** One-time WebSocket ticket. The value kept here is a digest, never the raw ticket. */
public record WsTicket(
        long id,
        String ticketHash,
        long userId,
        String sessionId,
        String roomCode,
        Instant expiresAt,
        Instant usedAt,
        Instant createdAt) {

    public WsTicket(String ticketHash, long userId, String sessionId, String roomCode,
                    Instant expiresAt, Instant usedAt, Instant createdAt) {
        this(0L, ticketHash, userId, sessionId, roomCode, expiresAt, usedAt, createdAt);
    }

    public boolean belongsTo(long expectedUserId, String expectedSessionId, String expectedRoomCode) {
        return userId == expectedUserId
                && Objects.equals(sessionId, expectedSessionId)
                && Objects.equals(roomCode, expectedRoomCode);
    }

    public boolean isUsableAt(Instant now) {
        return now != null && usedAt == null && expiresAt != null && now.isBefore(expiresAt);
    }

    public WsTicket withUsedAt(Instant value) {
        return new WsTicket(id, ticketHash, userId, sessionId, roomCode, expiresAt, value, createdAt);
    }
}
