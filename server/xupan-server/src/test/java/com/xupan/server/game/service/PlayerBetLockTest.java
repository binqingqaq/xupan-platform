package com.xupan.server.game.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerBetLockTest {

    @Test
    void serializesCallsForTheSamePlayer() throws Exception {
        PlayerBetLock lock = new PlayerBetLock();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        Runnable task = () -> lock.withPlayerLock(7L, () -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            started.countDown();
            try {
                Thread.sleep(60);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return null;
        });

        Thread first = new Thread(task);
        Thread second = new Thread(task);
        first.start();
        second.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(maxActive).as("同一玩家同时进入下注流程的线程数").hasValue(1);
    }

    @Test
    void allowsDifferentPlayersToRunInParallel() throws Exception {
        PlayerBetLock lock = new PlayerBetLock();
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable first = () -> lock.withPlayerLock(1L, () -> {
            bothInside.countDown();
            await(release);
            return null;
        });
        Runnable second = () -> lock.withPlayerLock(2L, () -> {
            bothInside.countDown();
            await(release);
            return null;
        });

        Thread threadA = new Thread(first);
        Thread threadB = new Thread(second);
        threadA.start();
        threadB.start();
        assertThat(bothInside.await(5, TimeUnit.SECONDS)).as("不同玩家可以同时进入下注流程").isTrue();
        release.countDown();
        threadA.join(TimeUnit.SECONDS.toMillis(5));
        threadB.join(TimeUnit.SECONDS.toMillis(5));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
