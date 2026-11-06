package com.xupan.server.display;

import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MobileDisplayMacauService {

    private static final Logger log = LoggerFactory.getLogger(MobileDisplayMacauService.class);
    private static final String SOURCE_CODE = MobileDisplayLotterySnapshotRepository.MACAU_SOURCE_CODE;

    private final HttpMobileDisplayMacauClient client;
    private final MobileDisplayMacauScriptMapper mapper;
    private final MobileDisplayLotteryMapper lotteryMapper;
    private final MobileDisplayLotterySnapshotRepository repository;
    private final MobileDisplayMacauProperties properties;
    private final Object refreshLock = new Object();
    private final AtomicReference<CachedResponse> cache = new AtomicReference<>();

    public MobileDisplayMacauService(HttpMobileDisplayMacauClient client,
                                    MobileDisplayMacauScriptMapper mapper,
                                    MobileDisplayLotteryMapper lotteryMapper,
                                    MobileDisplayLotterySnapshotRepository repository,
                                    MobileDisplayMacauProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.lotteryMapper = lotteryMapper;
        this.repository = repository;
        this.properties = properties;
    }

    public MobileDisplayHomeResponse getDraws() {
        CachedResponse cached = cache.get();
        if (cached != null && !isExpired(cached.cachedAt())) {
            return cached.response();
        }
        synchronized (refreshLock) {
            cached = cache.get();
            if (cached != null && !isExpired(cached.cachedAt())) {
                return cached.response();
            }
            MobileDisplayHomeResponse response = fetchFreshOrFallback();
            cache.set(new CachedResponse(response, Instant.now()));
            return response;
        }
    }

    private MobileDisplayHomeResponse fetchFreshOrFallback() {
        try {
            MobileDisplayMacauScriptMapper.ParsedMacau parsed = mapper.parse(
                    client.fetchScripts(),
                    properties.getNewDrawTime(),
                    properties.getOldDrawTime());
            Instant fetchedAt = Instant.now();
            repository.saveAll(SOURCE_CODE, parsed.cards(), fetchedAt);
            return new MobileDisplayHomeResponse(
                    SOURCE_CODE,
                    parsed.sourceTime(),
                    fetchedAt,
                    false,
                    parsed.cards());
        } catch (RuntimeException exception) {
            log.warn("移动端展示新老澳彩刷新失败，尝试读取最近快照: {}", exception.getMessage());
            return loadLatestSnapshot()
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "MOBILE_DISPLAY_MACAU_UNAVAILABLE",
                            "新老澳彩数据暂时不可用"));
        }
    }

    private java.util.Optional<MobileDisplayHomeResponse> loadLatestSnapshot() {
        List<String> cardJson = repository.findLatestCardJson(SOURCE_CODE);
        if (cardJson.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<MobileDisplayHomeResponse.LotteryCard> cards = cardJson.stream()
                .map(lotteryMapper::parseStoredCard)
                .sorted((left, right) -> Integer.compare(left.lotCode(), right.lotCode()))
                .toList();
        Instant fetchedAt = repository.findLatestFetchedAt(SOURCE_CODE).orElse(Instant.now());
        return java.util.Optional.of(new MobileDisplayHomeResponse(
                SOURCE_CODE,
                fetchedAt,
                fetchedAt,
                true,
                cards));
    }

    private boolean isExpired(Instant cachedAt) {
        Duration ttl = properties.getCacheTtl();
        return ttl == null || ttl.isZero() || ttl.isNegative()
                || cachedAt.plus(ttl).isBefore(Instant.now());
    }

    private record CachedResponse(MobileDisplayHomeResponse response, Instant cachedAt) {
    }
}
