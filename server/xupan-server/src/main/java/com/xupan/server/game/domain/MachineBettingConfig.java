package com.xupan.server.game.domain;

import java.math.BigDecimal;

/**
 * BY220 机器账号（代理）在下注、撤单和报表中生效的配置快照。
 *
 * <p>对应 BY220 {@code x_robot} 的 {@code PeiLv}、{@code tePeilv}、{@code fengpan}、
 * {@code cancel}、{@code isOpen}、{@code ls}、{@code FanShui}、{@code teFanshui}。
 * 玩家没有归属机器时使用 {@link #DEFAULT}；机器赔率为0表示机器配置不可用，禁止全局静默回退。</p>
 *
 * @param oddsRate            普通玩法净赢百分数（BY220 PeiLv），0 表示未配置
 * @param specialOddsRate     特码含本金倍数（BY220 tePeilv），0 表示未配置
 * @param closeSeconds        距开奖前多少秒封盘（BY220 fengpan），0 表示不额外提前
 * @param cancelSeconds       下注后多少秒内可撤单（BY220 cancel），0 表示关闭取消
 * @param manualBettingOpen   机器手动开盘状态（BY220 isOpen）；只影响新下注
 * @param rebateMode          返水口径 SINGLE/DOUBLE（BY220 ls）
 * @param rebateRate          普通返水百分数（BY220 FanShui）
 * @param specialRebateRate   特码返水（BY220 teFanshui）
 */
public record MachineBettingConfig(BigDecimal oddsRate,
                                   BigDecimal specialOddsRate,
                                   int closeSeconds,
                                   int cancelSeconds,
                                   boolean manualBettingOpen,
                                   String rebateMode,
                                   BigDecimal rebateRate,
                                   BigDecimal specialRebateRate,
                                   long machineId,
                                   int version,
                                   BettingLimits limits) {

    public static final String SINGLE = "SINGLE";
    public static final String DOUBLE = "DOUBLE";

    public static final MachineBettingConfig DEFAULT = new MachineBettingConfig(
            BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, true, SINGLE, BigDecimal.ZERO, BigDecimal.ZERO,
            0, 0, BettingLimits.UNLIMITED);

    public MachineBettingConfig(BigDecimal oddsRate, BigDecimal specialOddsRate, int closeSeconds,
                                int cancelSeconds, String rebateMode,
                                BigDecimal rebateRate, BigDecimal specialRebateRate) {
        this(oddsRate, specialOddsRate, closeSeconds, cancelSeconds, true, rebateMode,
                rebateRate, specialRebateRate, 0, 0, BettingLimits.UNLIMITED);
    }

    public MachineBettingConfig(BigDecimal oddsRate, BigDecimal specialOddsRate, int closeSeconds,
                                int cancelSeconds, boolean manualBettingOpen, String rebateMode,
                                BigDecimal rebateRate, BigDecimal specialRebateRate) {
        this(oddsRate, specialOddsRate, closeSeconds, cancelSeconds, manualBettingOpen, rebateMode,
                rebateRate, specialRebateRate, 0, 0, BettingLimits.UNLIMITED);
    }

    public MachineBettingConfig(BigDecimal oddsRate, BigDecimal specialOddsRate, int closeSeconds,
                                int cancelSeconds, String rebateMode, BigDecimal rebateRate,
                                BigDecimal specialRebateRate, long machineId, int version, BettingLimits limits) {
        this(oddsRate, specialOddsRate, closeSeconds, cancelSeconds, true, rebateMode, rebateRate,
                specialRebateRate, machineId, version, limits);
    }

    public MachineBettingConfig {
        oddsRate = oddsRate == null ? BigDecimal.ZERO : oddsRate;
        specialOddsRate = specialOddsRate == null ? BigDecimal.ZERO : specialOddsRate;
        closeSeconds = Math.max(0, closeSeconds);
        cancelSeconds = Math.max(0, cancelSeconds);
        rebateMode = DOUBLE.equalsIgnoreCase(rebateMode) ? DOUBLE : SINGLE;
        rebateRate = rebateRate == null ? BigDecimal.ZERO : rebateRate;
        specialRebateRate = specialRebateRate == null ? BigDecimal.ZERO : specialRebateRate;
        limits = limits == null ? BettingLimits.UNLIMITED : limits;
    }

    /** 机器普通赔率必须为正数才能接单。 */
    public boolean hasConfiguredOdds() {
        return oddsRate.signum() > 0;
    }

    /** 机器特码赔率必须为正数才能接单。 */
    public boolean hasConfiguredSpecialOdds() {
        return specialOddsRate.signum() > 0;
    }

    /** 是否关闭撤单（BY220 cancel=0）。 */
    public boolean cancellationDisabled() {
        return cancelSeconds <= 0;
    }

    public boolean doubleRebate() {
        return DOUBLE.equals(rebateMode);
    }

    public record BettingLimits(BigDecimal total, BigDecimal positive, BigDecimal angle,
                                BigDecimal strict, BigDecimal tong, BigDecimal car,
                                BigDecimal special, BigDecimal oddEven, BigDecimal bigSmall,
                                BigDecimal fan, BigDecimal add, BigDecimal playerMax,
                                BigDecimal playerMin) {
        public static final BettingLimits UNLIMITED = new BettingLimits(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public BettingLimits {
            total = amount(total);
            positive = amount(positive);
            angle = amount(angle);
            strict = amount(strict);
            tong = amount(tong);
            car = amount(car);
            special = amount(special);
            oddEven = amount(oddEven);
            bigSmall = amount(bigSmall);
            fan = amount(fan);
            add = amount(add);
            playerMax = amount(playerMax);
            playerMin = amount(playerMin);
        }

        private static BigDecimal amount(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
