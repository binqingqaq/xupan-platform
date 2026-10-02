package com.xupan.server.game.domain;

import java.math.BigDecimal;

/**
 * BY220 机器账号（代理）在下注、撤单和报表中生效的配置快照。
 *
 * <p>对应 BY220 {@code x_robot} 的 {@code PeiLv}、{@code tePeilv}、{@code fengpan}、
 * {@code cancel}、{@code isOpen}、{@code ls}、{@code FanShui}、{@code teFanshui}。
 * 玩家没有归属机器时使用 {@link #DEFAULT}，其取值为 0/true，表示赔率回退全局配置、
 * 不额外提前封盘并保持取消能力。</p>
 *
 * @param oddsRate            普通玩法净赢百分数（BY220 PeiLv），0 表示未配置
 * @param specialOddsRate     特码含本金倍数（BY220 tePeilv），0 表示未配置
 * @param closeSeconds        距开奖前多少秒封盘（BY220 fengpan），0 表示不额外提前
 * @param cancelSeconds       下注后多少秒内可撤单（BY220 cancel），0 表示关闭取消
 * @param boardOpen           是否开盘（BY220 isOpen）
 * @param rebateMode          返水口径 SINGLE/DOUBLE（BY220 ls）
 * @param rebateRate          普通返水百分数（BY220 FanShui）
 * @param specialRebateRate   特码返水（BY220 teFanshui）
 */
public record MachineBettingConfig(BigDecimal oddsRate,
                                   BigDecimal specialOddsRate,
                                   int closeSeconds,
                                   int cancelSeconds,
                                   boolean boardOpen,
                                   String rebateMode,
                                   BigDecimal rebateRate,
                                   BigDecimal specialRebateRate) {

    public static final String SINGLE = "SINGLE";
    public static final String DOUBLE = "DOUBLE";

    public static final MachineBettingConfig DEFAULT = new MachineBettingConfig(
            BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, true, SINGLE, BigDecimal.ZERO, BigDecimal.ZERO);

    public MachineBettingConfig {
        oddsRate = oddsRate == null ? BigDecimal.ZERO : oddsRate;
        specialOddsRate = specialOddsRate == null ? BigDecimal.ZERO : specialOddsRate;
        closeSeconds = Math.max(0, closeSeconds);
        cancelSeconds = Math.max(0, cancelSeconds);
        rebateMode = DOUBLE.equalsIgnoreCase(rebateMode) ? DOUBLE : SINGLE;
        rebateRate = rebateRate == null ? BigDecimal.ZERO : rebateRate;
        specialRebateRate = specialRebateRate == null ? BigDecimal.ZERO : specialRebateRate;
    }

    /** 是否配置了普通玩法赔率；未配置时回退全局赔率。 */
    public boolean hasConfiguredOdds() {
        return oddsRate.signum() > 0;
    }

    /** 是否配置了特码赔率；未配置时回退全局赔率。 */
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
}
