package com.xupan.server.display;

import java.time.Instant;
import java.util.List;

public record MobileDisplayHomeResponse(
        String source,
        Instant serverTime,
        Instant fetchedAt,
        boolean stale,
        List<LotteryCard> cards
) {
    public record LotteryCard(
            String key,
            int lotCode,
            String name,
            String issue,
            Instant nextDrawAt,
            String countdownFormat,
            List<String> numbers,
            List<String> numberColors,
            List<String> summary
    ) {
    }
}
