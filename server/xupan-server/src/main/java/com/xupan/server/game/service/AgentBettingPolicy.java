package com.xupan.server.game.service;

import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 代理机器赔率、封盘与取消规则的唯一入口。
 *
 * <p>对齐 BY220：
 * <ul>
 *   <li>普通玩法展示/结算赔率 = {@code 1 + num × PeiLv/100}（{@code common.php:4562-4566}）</li>
 *   <li>特码赔率直接使用 {@code tePeilv}（含本金倍数，{@code common.php:4568-4571}）</li>
 *   <li>封盘 = 距开奖 {@code fengpan} 秒（{@code common.php:2133-2135}）</li>
 *   <li>取消 = 下注后 {@code cancel} 秒内有效，{@code cancel=0} 关闭（{@code common.php:2067-2106}）</li>
 * </ul>
 *
 * <p>本业务只使用所属机器的 P/T。缺少机器或 P/T 未配置时拒绝报价，不能回退全局赔率。</p>
 */
@Service
public class AgentBettingPolicy {

    public enum DisplayOddsRuleKey {
        BASE, FAN, STRICT, CAR, NONE_PAIR, NONE_TRIPLE, TONG, SPECIAL
    }

    /** 机器手动封盘（BY220 isOpen=0），与期次封盘时间独立。 */
    public static final String MANUALLY_CLOSED = "GAME_MACHINE_MANUALLY_CLOSED";
    /** 已到该机器的封盘时间（BY220 fengpan）。 */
    public static final String MACHINE_CLOSED = "GAME_MACHINE_CLOSED";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final Map<PlayType, BigDecimal> WEIGHTS = weights();

    /**
     * 按 BY220 玩家端硬编码的玩法权重换算赔率。
     *
     * @param playType  玩法
     * @param globalOdds 全局赔率，机器未配置时使用
     * @param config    机器配置
     */
    public BigDecimal effectiveOdds(PlayType playType, BigDecimal globalOdds, MachineBettingConfig config) {
        return effectiveOdds(playType, globalOdds, config, java.util.List.of());
    }

    public BigDecimal effectiveOdds(PlayType playType, BigDecimal globalOdds, MachineBettingConfig config,
                                    java.util.List<Integer> parameters) {
        if (playType == null) {
            throw new IllegalArgumentException("玩法不能为空");
        }
        MachineBettingConfig machine = config == null ? MachineBettingConfig.DEFAULT : config;
        if (playType == PlayType.SPECIAL) {
            if (machine.hasConfiguredSpecialOdds()) {
                return machine.specialOddsRate().setScale(4, RoundingMode.UNNECESSARY);
            }
            throw new IllegalStateException("MACHINE_SPECIAL_ODDS_UNAVAILABLE");
        }
        if (machine.hasConfiguredOdds()) {
            BigDecimal weight = playType == PlayType.NONE && parameters != null && parameters.size() == 3
                    ? new BigDecimal("0.5") : weight(playType);
            BigDecimal converted = ONE.add(machine.oddsRate().multiply(weight)
                    .divide(HUNDRED, 12, RoundingMode.HALF_UP));
            return converted.max(ONE).setScale(3, RoundingMode.DOWN);
        }
        throw new IllegalStateException("MACHINE_ODDS_UNAVAILABLE");
    }

    /** 生成玩家格子展示值；CAR 使用 BY220 页面系数 0.333，与下注结算权重 1/3 分开。 */
    public BigDecimal displayOdds(DisplayOddsRuleKey ruleKey, MachineBettingConfig config) {
        if (ruleKey == null) throw new IllegalArgumentException("展示赔率规则不能为空");
        MachineBettingConfig machine = config == null ? MachineBettingConfig.DEFAULT : config;
        if (ruleKey == DisplayOddsRuleKey.SPECIAL) {
            if (machine.hasConfiguredSpecialOdds()) {
                return machine.specialOddsRate().setScale(4, RoundingMode.UNNECESSARY);
            }
            throw new IllegalStateException("MACHINE_SPECIAL_ODDS_UNAVAILABLE");
        }
        if (!machine.hasConfiguredOdds()) throw new IllegalStateException("MACHINE_ODDS_UNAVAILABLE");

        BigDecimal weight = switch (ruleKey) {
            case BASE, NONE_PAIR -> ONE;
            case FAN -> new BigDecimal("3");
            case STRICT -> new BigDecimal("2");
            case CAR -> new BigDecimal("0.333");
            case NONE_TRIPLE, TONG -> new BigDecimal("0.5");
            case SPECIAL -> throw new IllegalStateException("unreachable");
        };
        BigDecimal converted = ONE.add(machine.oddsRate().multiply(weight)
                .divide(HUNDRED, 12, RoundingMode.HALF_UP));
        return converted.max(ONE).setScale(3, RoundingMode.DOWN);
    }

    /** 玩法权重：番=3、念/严=2、车/推=1/3、通=1/2，其余为 1。 */
    public BigDecimal weight(PlayType playType) {
        BigDecimal weight = WEIGHTS.get(playType);
        return weight == null ? ONE : weight;
    }

    /** 下注前校验该机器是否允许下注；返回拒绝原因码。 */
    public Optional<String> rejectBet(MachineBettingConfig config, GameDataRepository.IssueRecord issue, Instant now) {
        MachineBettingConfig machine = config == null ? MachineBettingConfig.DEFAULT : config;
        if (!machine.manualBettingOpen()) {
            return Optional.of(MANUALLY_CLOSED);
        }
        if (isClosedForBetting(machine, issue, now)) {
            return Optional.of(MACHINE_CLOSED);
        }
        return Optional.empty();
    }

    /** 是否已进入该机器的封盘窗口（BY220 fengpan 之后禁止下注和取消）。 */
    public boolean isClosedForBetting(MachineBettingConfig config, GameDataRepository.IssueRecord issue, Instant now) {
        MachineBettingConfig machine = config == null ? MachineBettingConfig.DEFAULT : config;
        if (machine.closeSeconds() <= 0 || issue == null || issue.drawEndsAt() == null || now == null) {
            return false;
        }
        // BY220 判断为 dtOpen - now < fengpan，剩余时间正好等于 fengpan 时仍可下注。
        return now.isAfter(issue.drawEndsAt().minusSeconds(machine.closeSeconds()));
    }

    /** 撤单候选窗口起点：BY220 只允许下注后 cancel 秒内的注单撤单。 */
    public Instant cancellationWindowStart(MachineBettingConfig config, Instant now) {
        MachineBettingConfig machine = config == null ? MachineBettingConfig.DEFAULT : config;
        return now.minusSeconds(machine.cancelSeconds());
    }

    public static String rejectionMessage(String reason) {
        return switch (reason) {
            case MANUALLY_CLOSED -> "已停止下注";
            case MACHINE_CLOSED -> "已到封盘时间，禁止下注";
            default -> "当前不能下注";
        };
    }

    private static Map<PlayType, BigDecimal> weights() {
        EnumMap<PlayType, BigDecimal> values = new EnumMap<>(PlayType.class);
        values.put(PlayType.FAN, new BigDecimal("3"));
        values.put(PlayType.ANGLE, ONE);
        values.put(PlayType.CAR, BigDecimal.ONE.divide(new BigDecimal("3"), 12, RoundingMode.HALF_UP));
        values.put(PlayType.STRICT, new BigDecimal("2"));
        values.put(PlayType.ADD, ONE);
        values.put(PlayType.POSITIVE, ONE);
        values.put(PlayType.TONG, new BigDecimal("0.5"));
        values.put(PlayType.NONE, ONE);
        values.put(PlayType.ODD_EVEN, ONE);
        values.put(PlayType.BIG_SMALL, ONE);
        return Map.copyOf(values);
    }
}
