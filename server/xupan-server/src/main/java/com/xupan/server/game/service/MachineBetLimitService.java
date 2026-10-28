package com.xupan.server.game.service;

import com.xupan.server.game.domain.BetQuotaBucket;
import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MachineBetLimitService {

    private final GameDataRepository repository;

    public MachineBetLimitService(GameDataRepository repository) {
        this.repository = repository;
    }

    public void validateBatch(long accountId, String gameCode, String issueNumber,
                              MachineBettingConfig config, List<Instruction> instructions) {
        if (config == null || config.machineId() <= 0) {
            throw new IllegalStateException("MACHINE_CONFIG_UNAVAILABLE");
        }
        if (repository.countUnclassifiedQuotaBets(accountId, gameCode, issueNumber) > 0) {
            throw new IllegalStateException("ISSUE_HAS_UNCLASSIFIED_BETS");
        }
        Usage usage = new Usage();
        usage.total = money(repository.findIssueStakeTotal(accountId, config.machineId(), gameCode, issueNumber));
        for (GameDataRepository.QuotaUsage existing : repository.findQuotaUsageByIssue(
                accountId, config.machineId(), gameCode, issueNumber)) {
            BigDecimal amount = money(existing.stake());
            if (BetQuotaBucket.SPECIAL_NUMBER.name().equals(existing.quotaBucket())) {
                if (existing.numberValue() != null) {
                    usage.specialByNumber.merge(existing.numberValue(), amount, BigDecimal::add);
                }
            } else if (existing.quotaBucket() != null) {
                usage.byBucket.merge(existing.selectionKey(), amount, BigDecimal::add);
            }
        }
        BigDecimal max = config.limits().playerMax();
        BigDecimal min = config.limits().playerMin();
        for (Instruction instruction : instructions) {
            BigDecimal stake = money(instruction.stake());
            if (min.signum() > 0 && stake.compareTo(min) < 0) {
                reject(instruction, "玩家最小注额" + amount(min));
            }
            if (max.signum() > 0 && stake.compareTo(max) > 0) {
                reject(instruction, "玩家最高注额" + amount(max));
            }
            BigDecimal totalLimit = config.limits().total();
            if (totalLimit.signum() <= 0) {
                reject(instruction, "单场总限额未配置");
            }
            if (exceeded(usage.total, stake, totalLimit)) {
                reject(instruction, "超过单场总限额" + amount(totalLimit)
                        + "，剩余可下" + amount(remaining(totalLimit, usage.total)));
            }
            BetQuotaBucket bucket = BetQuotaBucket.forBet(instruction.playType(), instruction.parameters());
            BigDecimal quotaLimit = limit(config.limits(), bucket);
            if (quotaLimit.signum() <= 0) {
                reject(instruction, label(bucket) + "限额未配置");
            }
            if (instruction.playType() == PlayType.SPECIAL) {
                BigDecimal unit = money(instruction.unitStake());
                for (Integer number : instruction.parameters()) {
                    BigDecimal used = usage.specialByNumber.getOrDefault(number, BigDecimal.ZERO);
                    if (exceeded(used, unit, quotaLimit)) {
                        reject(instruction, "超过" + number + "特限额" + amount(quotaLimit)
                                + "，剩余可下" + amount(remaining(quotaLimit, used)));
                    }
                    usage.specialByNumber.merge(number, unit, BigDecimal::add);
                }
            } else {
                String key = instruction.quotaSelectionKey();
                BigDecimal used = usage.byBucket.getOrDefault(key, BigDecimal.ZERO);
                if (exceeded(used, stake, quotaLimit)) {
                    reject(instruction, "超过" + label(bucket) + "限额" + amount(quotaLimit)
                            + "，剩余可下" + amount(remaining(quotaLimit, used)));
                }
                usage.byBucket.merge(key, stake, BigDecimal::add);
            }
            usage.total = usage.total.add(stake);
        }
    }

    private static BigDecimal limit(MachineBettingConfig.BettingLimits limits, BetQuotaBucket bucket) {
        return switch (bucket) {
            case POSITIVE -> limits.positive();
            case ANGLE -> limits.angle();
            case STRICT -> limits.strict();
            case TONG -> limits.tong();
            case CAR -> limits.car();
            case SPECIAL_NUMBER -> limits.special();
            case ODD_EVEN -> limits.oddEven();
            case BIG_SMALL -> limits.bigSmall();
            case FAN -> limits.fan();
            case ADD -> limits.add();
            case TOTAL -> limits.total();
        };
    }

    private static String label(BetQuotaBucket bucket) {
        return switch (bucket) {
            case POSITIVE -> "正";
            case ANGLE -> "角";
            case STRICT -> "念";
            case TONG -> "通";
            case CAR -> "车";
            case SPECIAL_NUMBER -> "特码";
            case ODD_EVEN -> "单双";
            case BIG_SMALL -> "大小";
            case FAN -> "番";
            case ADD -> "加";
            case TOTAL -> "单场总";
        };
    }

    private static boolean exceeded(BigDecimal used, BigDecimal requested, BigDecimal limit) {
        return limit.signum() > 0 && used.add(requested).compareTo(limit) > 0;
    }

    private static BigDecimal remaining(BigDecimal limit, BigDecimal used) {
        return limit.subtract(used).max(BigDecimal.ZERO);
    }

    private static String amount(BigDecimal value) {
        return money(value).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static void reject(Instruction instruction, String reason) {
        throw new BetLimitExceededException(instruction.playType(), instruction.parameters(),
                money(instruction.stake()), reason);
    }

    public record Instruction(PlayType playType, List<Integer> parameters, BigDecimal stake,
                              BigDecimal unitStake, String quotaSelectionKey) {
        public Instruction(PlayType playType, List<Integer> parameters, BigDecimal stake, BigDecimal unitStake) {
            this(playType, parameters, stake, unitStake, null);
        }

        public Instruction {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            if (stake == null || stake.signum() <= 0) throw new IllegalArgumentException("下注金额无效");
            if (unitStake == null) unitStake = stake;
            if (quotaSelectionKey == null) quotaSelectionKey = BetQuotaBucket.ruleKey(playType, parameters);
        }
    }

    private static final class Usage {
        private BigDecimal total = BigDecimal.ZERO.setScale(2);
        private final Map<String, BigDecimal> byBucket = new HashMap<>();
        private final Map<Integer, BigDecimal> specialByNumber = new HashMap<>();
    }
}
