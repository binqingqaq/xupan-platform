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
 * <p>机器赔率未配置（0/NULL）时回退全局 {@code game_odds}，因此存量机器的行为保持不变。</p>
 */
@Service
public class AgentBettingPolicy {

    /** 机器已闭盘（BY220 isOpen=0）。 */
    public static final String BOARD_CLOSED = "GAME_BOARD_CLOSED";
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
        if (playType == null) {
            throw new IllegalArgumentException("玩法不能为空");
        }
        MachineBettingConfig machine = config == null ? MachineBettingConfig.DEFAULT : config;
        if (playType == PlayType.SPECIAL) {
            if (machine.hasConfiguredSpecialOdds()) {
                return scale3(machine.specialOddsRate().max(ONE));
            }
            return scale3(requireGlobal(globalOdds));
        }
        if (machine.hasConfiguredOdds()) {
            BigDecimal converted = ONE.add(machine.oddsRate().multiply(weight(playType))
                    .divide(HUNDRED, 6, RoundingMode.HALF_UP));
            return scale3(converted.max(ONE));
        }
        return scale3(requireGlobal(globalOdds));
    }

    /** 玩法权重：番=3、念/严=2、车/推=1/3、通=1/2，其余为 1。 */
    public BigDecimal weight(PlayType playType) {
        BigDecimal weight = WEIGHTS.get(playType);
        return weight == null ? ONE : weight;
    }

    /** 下注前校验该机器是否允许下注；返回拒绝原因码。 */
    public Optional<String> rejectBet(MachineBettingConfig config, GameDataRepository.IssueRecord issue, Instant now) {
        MachineBettingConfig machine = config == null ? MachineBettingConfig.DEFAULT : config;
        if (!machine.boardOpen()) {
            return Optional.of(BOARD_CLOSED);
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
            case BOARD_CLOSED -> "本机已闭盘，禁止下注";
            case MACHINE_CLOSED -> "已到封盘时间，禁止下注";
            default -> "当前不能下注";
        };
    }

    private static BigDecimal requireGlobal(BigDecimal globalOdds) {
        if (globalOdds == null) {
            throw new IllegalArgumentException("玩法赔率不存在");
        }
        return globalOdds;
    }

    private static BigDecimal scale3(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static Map<PlayType, BigDecimal> weights() {
        EnumMap<PlayType, BigDecimal> values = new EnumMap<>(PlayType.class);
        values.put(PlayType.FAN, new BigDecimal("3"));
        values.put(PlayType.ANGLE, ONE);
        values.put(PlayType.CAR, new BigDecimal("0.333333"));
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
