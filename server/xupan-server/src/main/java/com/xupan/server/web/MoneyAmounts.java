package com.xupan.server.web;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 全站金额/虚拟积分的统一两位小数口径。
 *
 * <p><b>为什么要有这个类</b>：修复前同一条"两位小数"规则散落在至少四处，且口径互相矛盾——</p>
 * <ul>
 *   <li>{@code VirtualWalletService.normalizedAmount}：{@code scale() > 2} → 400（正确）；</li>
 *   <li>{@code AgentLedgerService.scaled}：{@code value.setScale(2)} 无舍入模式 → 抛
 *       {@link ArithmeticException} → 全局兜底 500（错误）；</li>
 *   <li>子账号上分/下分、机器给玩家上下分、超管改子账号积分：{@code setScale(2, HALF_UP)}
 *       → 把 0.001 **静默**变成 0.00 再当作"本次不改动"（错误）。</li>
 * </ul>
 *
 * <p><b>本类固定两条口径</b>：</p>
 * <ol>
 *   <li>{@link #requireTwoDecimals}：**调用方传进来的原始金额/目标值**一律严格校验，
 *       超过两位小数直接 400，绝不静默四舍五入，也绝不冒 500；</li>
 *   <li>{@link #rounded}：**由减法、取负算出来的差额**（例如
 *       {@code target.subtract(balance)}、{@code amount.negate()}）继续按
 *       {@link RoundingMode#HALF_UP} 收敛到两位——它不是用户输入，报"超精度"没有意义。</li>
 * </ol>
 *
 * <p>计划依据：{@code docs/plans/2026-10-08-后台入口独立登录与积分精度与失效重登实施计划.md}
 * §3.5「全站金额/积分统一 2 位小数，超精度一律 400 明确报错，禁止静默舍入、禁止 500」。</p>
 *
 * <p><b>合规</b>：只处理虚拟积分，不涉及任何真实资金。</p>
 */
public final class MoneyAmounts {

    /**
     * 整数部分允许的最大位数。
     *
     * <p>目标列与账本列都是 {@code DECIMAL(18,2)}，整数部分最多 16 位；超过就交给数据库报错
     * （表现为 500），所以在服务层先拦成 400。</p>
     */
    private static final int MAX_INTEGER_DIGITS = 16;

    private MoneyAmounts() {
    }

    /**
     * 严格两位小数校验：金额/积分的**原始输入值**必须走这里。
     *
     * @param amount        调用方传入的金额或目标值
     * @param code          业务错误码，必须由调用方给出稳定值（前端按码做中文映射）
     * @param invalidMessage {@code null} 或整数部分超出列容量时的文案
     * @param scaleMessage   小数位超过两位时的文案（工程口径：「X最多两位小数」）
     * @return 精度恰好为 2 的金额（仅补零，不做任何舍入）
     * @throws BusinessException 400：格式无效或超过两位小数
     */
    public static BigDecimal requireTwoDecimals(BigDecimal amount, String code,
                                                String invalidMessage, String scaleMessage) {
        if (amount == null || amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw BusinessException.badRequest(code, invalidMessage);
        }
        if (amount.scale() > 2) {
            throw BusinessException.badRequest(code, scaleMessage);
        }
        try {
            // UNNECESSARY：只在"不需要舍入"时成功。上面已挡下 scale > 2，这里是防御性兜底，
            // 保证任何未来的改动都不会让本方法退化成静默四舍五入。
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw BusinessException.badRequest(code, scaleMessage);
        }
    }

    /**
     * 差额收敛：**由减法或取负算出来的值**用这里，保留历史行为（{@code HALF_UP} 到两位）。
     *
     * @throws NullPointerException 差额不允许为 {@code null}；可空场景请用
     *                              {@link #roundedOrZero(BigDecimal)}
     */
    public static BigDecimal rounded(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 同 {@link #rounded}，但 {@code null} 归一化为 {@code 0.00}。 */
    public static BigDecimal roundedOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
