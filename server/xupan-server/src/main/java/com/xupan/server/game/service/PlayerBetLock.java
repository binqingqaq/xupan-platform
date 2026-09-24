package com.xupan.server.game.service;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Serializes the bet writing flow of one player inside a single instance.
 *
 * <p>The quota check reads the bets already committed for the current issue and then inserts a new
 * bet, so two simultaneous requests of the same player could otherwise both read the same remaining
 * quota and both pass. The caller must hold this lock across the whole call, including the
 * transaction commit, so the next request of that player observes the previous one.
 *
 * <p>The chat message path does not need it: that transaction already takes a database row lock on
 * the chat room for its whole duration. This lock covers the bet endpoints that have no such lock
 * ({@code POST /api/demo/game/bets} and {@code POST /api/admin/test-players/{userCode}/bets}).
 *
 * <p>Locks are striped to keep the lock table bounded; an accidental collision only adds a little
 * waiting between two players. It is deliberately instance local, so a multi-instance deployment
 * still needs a database row lock or a distributed lock for those endpoints.
 */
@Component
public class PlayerBetLock {

    private static final int STRIPES = 64;

    private final Object[] locks = new Object[STRIPES];

    public PlayerBetLock() {
        for (int index = 0; index < STRIPES; index++) {
            locks[index] = new Object();
        }
    }

    public <T> T withPlayerLock(long userId, Supplier<T> action) {
        synchronized (lockFor(userId)) {
            return action.get();
        }
    }

    private Object lockFor(long userId) {
        return locks[(int) Math.floorMod(userId, STRIPES)];
    }
}
