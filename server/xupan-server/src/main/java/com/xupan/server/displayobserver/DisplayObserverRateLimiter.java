package com.xupan.server.displayobserver;

import com.xupan.server.auth.service.TokenService;
import com.xupan.server.web.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DisplayObserverRateLimiter {

    private static final int MAX_KEYS = 8192;

    private final Duration window;
    private final int exchangePerIp;
    private final int exchangePerBinding;
    private final int readsPerIp;
    private final int readsPerBinding;
    private final int websocketFramesPerBinding;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    @Autowired
    public DisplayObserverRateLimiter(
            @Value("${xupan.display-observer.rate-limit.window:60s}") Duration window,
            @Value("${xupan.display-observer.rate-limit.exchange-per-ip:60}") int exchangePerIp,
            @Value("${xupan.display-observer.rate-limit.exchange-per-binding:600}") int exchangePerBinding,
            @Value("${xupan.display-observer.rate-limit.reads-per-ip:600}") int readsPerIp,
            @Value("${xupan.display-observer.rate-limit.reads-per-binding:3000}") int readsPerBinding,
            @Value("${xupan.display-observer.rate-limit.websocket-frames-per-binding:1200}")
            int websocketFramesPerBinding) {
        this(window, exchangePerIp, exchangePerBinding, readsPerIp, readsPerBinding,
                websocketFramesPerBinding, Clock.systemUTC());
    }

    public DisplayObserverRateLimiter(Duration window, int exchangePerIp, int exchangePerBinding,
                                      int readsPerIp, int readsPerBinding) {
        this(window, exchangePerIp, exchangePerBinding, readsPerIp, readsPerBinding, 1200, Clock.systemUTC());
    }

    DisplayObserverRateLimiter(Duration window, int exchangePerIp, int exchangePerBinding,
                               int readsPerIp, int readsPerBinding, Clock clock) {
        this(window, exchangePerIp, exchangePerBinding, readsPerIp, readsPerBinding, 1200, clock);
    }

    DisplayObserverRateLimiter(Duration window, int exchangePerIp, int exchangePerBinding,
                               int readsPerIp, int readsPerBinding, int websocketFramesPerBinding, Clock clock) {
        this.window = window == null || window.isZero() || window.isNegative()
                ? Duration.ofMinutes(1) : window;
        this.exchangePerIp = positive(exchangePerIp, 60);
        this.exchangePerBinding = positive(exchangePerBinding, 600);
        this.readsPerIp = positive(readsPerIp, 600);
        this.readsPerBinding = positive(readsPerBinding, 3000);
        this.websocketFramesPerBinding = positive(websocketFramesPerBinding, 1200);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void checkExchange(String remoteAddress, String publicId) {
        Instant now = clock.instant();
        long retryAfter = Math.max(
                consume("exchange-ip", remoteAddress, exchangePerIp, now),
                consume("exchange-binding", publicId, exchangePerBinding, now));
        rejectWhenLimited(retryAfter);
    }

    public long checkRead(String remoteAddress, String publicId) {
        Instant now = clock.instant();
        return Math.max(
                consume("read-ip", remoteAddress, readsPerIp, now),
                consume("read-binding", publicId, readsPerBinding, now));
    }

    public long checkWebSocketFrame(String publicId) {
        return consume("websocket-binding", publicId, websocketFramesPerBinding, clock.instant());
    }

    private long consume(String category, String value, int limit, Instant now) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim();
        String key = TokenService.sha256(category + "|" + normalized);
        if (!windows.containsKey(key) && windows.size() >= MAX_KEYS) {
            evictExpired(now.minus(window));
            if (windows.size() >= MAX_KEYS) return Math.max(1, window.toSeconds());
        }
        Window rateWindow = windows.computeIfAbsent(key, ignored -> new Window());
        synchronized (rateWindow) {
            Instant cutoff = now.minus(window);
            while (!rateWindow.requests.isEmpty() && !rateWindow.requests.peekFirst().isAfter(cutoff)) {
                rateWindow.requests.removeFirst();
            }
            rateWindow.touchedAt = now;
            if (rateWindow.requests.size() >= limit) {
                return Math.max(1, Duration.between(now, rateWindow.requests.peekFirst().plus(window)).toSeconds());
            }
            rateWindow.requests.addLast(now);
        }
        if (operations.incrementAndGet() % 256 == 0 && windows.size() > MAX_KEYS) {
            evictExpired(now.minus(window));
        }
        return 0;
    }

    private void evictExpired(Instant cutoff) {
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            Window value = entry.getValue();
            synchronized (value) {
                if (value.touchedAt != null && value.touchedAt.isBefore(cutoff)) {
                    windows.remove(entry.getKey(), value);
                }
            }
        }
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static void rejectWhenLimited(long retryAfter) {
        if (retryAfter > 0) {
            throw BusinessException.tooManyRequests("DISPLAY_OBSERVER_RATE_LIMITED",
                    "只读观察请求过于频繁，请稍后重试", retryAfter);
        }
    }

    private static final class Window {
        private final Deque<Instant> requests = new ArrayDeque<>();
        private Instant touchedAt;
    }
}
