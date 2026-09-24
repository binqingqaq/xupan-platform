package com.xupan.server.display;

import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MobileDisplayHomeService {

    private static final Logger log = LoggerFactory.getLogger(MobileDisplayHomeService.class);

    private final ExternalLotteryListClient client;
    private final MobileDisplayLotteryMapper mapper;
    private final MobileDisplayLotterySnapshotRepository repository;
    private final MobileDisplayProperties properties;
    private final Object refreshLock = new Object();
    private final AtomicReference<CachedResponse> cache = new AtomicReference<>();

    public MobileDisplayHomeService(ExternalLotteryListClient client,
                                    MobileDisplayLotteryMapper mapper,
                                    MobileDisplayLotterySnapshotRepository repository,
                                    MobileDisplayProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.repository = repository;
        this.properties = properties;
    }

    public MobileDisplayHomeResponse getHome() {
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
            MobileDisplayLotteryMapper.ParsedHome parsed = mapper.parse(client.fetchHotLotteryList());
            Instant fetchedAt = Instant.now();
            repository.saveAll(parsed.cards(), fetchedAt);
            return new MobileDisplayHomeResponse(
                    MobileDisplayLotterySnapshotRepository.SOURCE_CODE,
                    parsed.serverTime(),
                    fetchedAt,
                    false,
                    parsed.cards());
        } catch (RuntimeException exception) {
            log.warn("移动端展示开奖接口刷新失败，尝试读取最近快照: {}", exception.getMessage());
            return loadLatestSnapshot()
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "MOBILE_DISPLAY_EXTERNAL_UNAVAILABLE",
                            "开奖数据暂时不可用"));
        }
    }

    private java.util.Optional<MobileDisplayHomeResponse> loadLatestSnapshot() {
        List<String> cardJson = repository.findLatestCardJson();
        if (cardJson.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<MobileDisplayHomeResponse.LotteryCard> cards = mapper.sortCards(
                cardJson.stream().map(mapper::parseStoredCard).toList());
        Instant fetchedAt = repository.findLatestFetchedAt().orElse(Instant.now());
        return java.util.Optional.of(new MobileDisplayHomeResponse(
                MobileDisplayLotterySnapshotRepository.SOURCE_CODE,
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
