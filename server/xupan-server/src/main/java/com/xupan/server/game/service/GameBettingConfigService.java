package com.xupan.server.game.service;

import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameBettingConfigRepository;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

@Service
public class GameBettingConfigService {

    private static final BigDecimal DEFAULT_SPECIAL_ODDS = new BigDecimal("18.000");

    private final GameBettingConfigRepository configRepository;
    private final GameDataRepository gameRepository;

    public GameBettingConfigService(GameBettingConfigRepository configRepository,
                                    GameDataRepository gameRepository) {
        this.configRepository = configRepository;
        this.gameRepository = gameRepository;
    }

    public BettingConfigView get() {
        GameBettingConfigRepository.ConfigRecord config = requireConfig();
        BigDecimal specialOdds = gameRepository.findOdds(PlayType.SPECIAL)
                .orElse(DEFAULT_SPECIAL_ODDS);
        return BettingConfigView.from(config, specialOdds);
    }

    @Transactional
    public BettingConfigView updateDisplay(Integer displayOdds, BigDecimal specialOdds, Integer specialRebate) {
        if (displayOdds == null || displayOdds < 0) {
            throw BusinessError.invalid("展示赔率必须是大于或等于 0 的整数");
        }
        if (specialRebate == null || specialRebate < 0) {
            throw BusinessError.invalid("特码返水必须是大于或等于 0 的整数");
        }
        BigDecimal normalizedOdds = normalizedSpecialOdds(specialOdds);
        GameBettingConfigRepository.ConfigRecord config = requireConfig();
        configRepository.updateDisplay(displayOdds, specialRebate);
        gameRepository.saveOdds(PlayType.SPECIAL, normalizedOdds);
        return BettingConfigView.from(config.withDisplay(displayOdds, specialRebate), normalizedOdds);
    }

    @Transactional
    public BettingConfigView updateLimits(LimitConfigRequest request) {
        if (request == null) {
            throw BusinessError.invalid("限额配置不能为空");
        }
        int[] values = {
                request.specialLimit(),
                request.issueTotalLimit(),
                request.positiveLimit(),
                request.angleLimit(),
                request.strictLimit(),
                request.tongLimit(),
                request.carLimit(),
                request.oddEvenLimit(),
                request.bigSmallLimit(),
                request.fanLimit(),
                request.addLimit(),
                request.playerMaxStake(),
                request.playerMinStake()
        };
        for (int value : values) {
            if (value <= 0) {
                throw BusinessError.invalid("所有限额和注额必须是大于 0 的整数");
            }
        }
        if (request.playerMinStake() > request.playerMaxStake()) {
            throw BusinessError.invalid("玩家最小注额不能大于玩家最高注额");
        }
        GameBettingConfigRepository.ConfigRecord current = requireConfig();
        GameBettingConfigRepository.ConfigRecord updated = current.withLimits(
                request.specialLimit(), request.issueTotalLimit(),
                request.positiveLimit(), request.angleLimit(), request.strictLimit(),
                request.tongLimit(), request.carLimit(), request.oddEvenLimit(),
                request.bigSmallLimit(), request.fanLimit(), request.addLimit(),
                request.playerMaxStake(), request.playerMinStake());
        if (configRepository.updateLimits(updated) != 1) {
            throw new IllegalStateException("限额配置保存失败");
        }
        return get();
    }

    public LimitUsage loadUsage(long accountId, String issueNumber) {
        Map<PlayType, BigDecimal> byType = new EnumMap<>(PlayType.class);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (GameDataRepository.BetUsage usage : gameRepository.findBetUsageByIssue(accountId, issueNumber)) {
            BigDecimal stake = money(usage.stake());
            byType.put(usage.playType(), stake);
            total = total.add(stake);
        }
        return new LimitUsage(total, byType);
    }

    public LimitRejection evaluate(PlayType playType, BigDecimal rawStake, LimitUsage usage) {
        GameBettingConfigRepository.ConfigRecord config = requireConfig();
        BigDecimal stake = money(rawStake);
        int minStake = config.playerMinStake();
        if (stake.compareTo(BigDecimal.valueOf(minStake)) < 0) {
            return new LimitRejection(LimitKind.MIN, playType, minStake, BigDecimal.ZERO);
        }

        int maxStake = config.playerMaxStake();
        BigDecimal remainingMax = BigDecimal.valueOf(maxStake).subtract(usage.total());
        if (stake.compareTo(remainingMax) > 0) {
            return new LimitRejection(LimitKind.PLAYER_MAX, playType, maxStake, clamp(remainingMax));
        }

        int issueLimit = config.issueTotalLimit();
        BigDecimal remainingIssue = BigDecimal.valueOf(issueLimit).subtract(usage.total());
        if (stake.compareTo(remainingIssue) > 0) {
            return new LimitRejection(LimitKind.ISSUE_TOTAL, playType, issueLimit, clamp(remainingIssue));
        }

        int typeLimit = limitFor(config, playType);
        BigDecimal remainingType = BigDecimal.valueOf(typeLimit).subtract(usage.forType(playType));
        if (stake.compareTo(remainingType) > 0) {
            return new LimitRejection(LimitKind.TYPE, playType, typeLimit, clamp(remainingType));
        }
        return null;
    }

    public void accept(LimitUsage usage, PlayType playType, BigDecimal rawStake) {
        usage.accept(playType, money(rawStake));
    }

    public String typeLimitLabel(PlayType playType) {
        return switch (playType) {
            case SPECIAL -> "特码";
            case POSITIVE -> "正";
            case ANGLE -> "角";
            case STRICT -> "念";
            case TONG, NONE -> "通";
            case CAR -> "车";
            case ODD_EVEN -> "单双";
            case BIG_SMALL -> "大小";
            case FAN -> "番";
            case ADD -> "加";
        };
    }

    /** Renders the public rejection reason, including the configured cap and the remaining quota. */
    public String describeRejection(LimitRejection rejection) {
        return switch (rejection.kind()) {
            case MIN -> "低于玩家最小注额" + rejection.limit();
            case PLAYER_MAX -> "超过玩家最高注额" + rejection.limit()
                    + "，剩余可下" + amount(rejection.remaining());
            case ISSUE_TOTAL -> "超过单场总限额" + rejection.limit()
                    + "，剩余可下" + amount(rejection.remaining());
            case TYPE -> "超过" + typeLimitLabel(rejection.playType()) + "限额" + rejection.limit()
                    + "，剩余可下" + amount(rejection.remaining());
        };
    }

    private GameBettingConfigRepository.ConfigRecord requireConfig() {
        return configRepository.find()
                .orElseThrow(() -> new IllegalStateException("下注配置不存在"));
    }

    private static int limitFor(GameBettingConfigRepository.ConfigRecord config, PlayType playType) {
        return switch (playType) {
            case SPECIAL -> config.specialLimit();
            case POSITIVE -> config.positiveLimit();
            case ANGLE -> config.angleLimit();
            case STRICT -> config.strictLimit();
            case TONG, NONE -> config.tongLimit();
            case CAR -> config.carLimit();
            case ODD_EVEN -> config.oddEvenLimit();
            case BIG_SMALL -> config.bigSmallLimit();
            case FAN -> config.fanLimit();
            case ADD -> config.addLimit();
        };
    }

    private static String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal normalizedSpecialOdds(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ONE) < 0 || value.scale() > 3) {
            throw BusinessError.invalid("特码赔率必须不小于 1，最多三位小数");
        }
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO.setScale(2) : money(value);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    public enum LimitKind {
        MIN,
        PLAYER_MAX,
        ISSUE_TOTAL,
        TYPE
    }

    public record LimitRejection(LimitKind kind, PlayType playType, int limit, BigDecimal remaining) {
    }

    public static final class LimitUsage {
        private BigDecimal total;
        private final Map<PlayType, BigDecimal> byType;

        private LimitUsage(BigDecimal total, Map<PlayType, BigDecimal> byType) {
            this.total = money(total);
            this.byType = new EnumMap<>(byType);
        }

        public BigDecimal total() {
            return total;
        }

        public BigDecimal forType(PlayType playType) {
            return byType.getOrDefault(playType, BigDecimal.ZERO.setScale(2));
        }

        private void accept(PlayType playType, BigDecimal stake) {
            total = total.add(stake);
            byType.merge(playType, stake, BigDecimal::add);
        }
    }

    public record BettingConfigView(
            int displayOdds,
            BigDecimal specialOdds,
            int specialRebate,
            int specialLimit,
            int issueTotalLimit,
            int positiveLimit,
            int angleLimit,
            int strictLimit,
            int tongLimit,
            int carLimit,
            int oddEvenLimit,
            int bigSmallLimit,
            int fanLimit,
            int addLimit,
            int playerMaxStake,
            int playerMinStake
    ) {
        static BettingConfigView from(GameBettingConfigRepository.ConfigRecord config, BigDecimal specialOdds) {
            return new BettingConfigView(config.displayOdds(), specialOdds, config.specialRebate(),
                    config.specialLimit(), config.issueTotalLimit(), config.positiveLimit(),
                    config.angleLimit(), config.strictLimit(), config.tongLimit(),
                    config.carLimit(), config.oddEvenLimit(), config.bigSmallLimit(),
                    config.fanLimit(), config.addLimit(), config.playerMaxStake(),
                    config.playerMinStake());
        }
    }

    public record LimitConfigRequest(
            int specialLimit,
            int issueTotalLimit,
            int positiveLimit,
            int angleLimit,
            int strictLimit,
            int tongLimit,
            int carLimit,
            int oddEvenLimit,
            int bigSmallLimit,
            int fanLimit,
            int addLimit,
            int playerMaxStake,
            int playerMinStake
    ) {
    }

    private static final class BusinessError {
        private BusinessError() {
        }

        static BusinessException invalid(String message) {
            return BusinessException.badRequest("BETTING_CONFIG_INVALID", message);
        }
    }
}
