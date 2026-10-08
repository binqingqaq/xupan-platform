package com.xupan.server.center.service;

import com.xupan.server.center.repository.CenterAgentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-C1：代理级风控上限、风控命中记录与异常行为识别。
 *
 * <p>阶段 B 的风控是全局常量（单笔 10 万 / 单期敞口 50 万），一个代理就能吃掉整期敞口。
 * 本测试验证代理级配置生效、命中留痕、以及"未配置时代理行为与阶段 B 完全一致"。</p>
 */
@SpringBootTest(properties = {
        "xupan.automation.enabled=false",
        "xupan.center.risk.behavior-window=10m",
        "xupan.center.risk.behavior-hit-threshold=5"
})
@ActiveProfiles("test")
@DisplayName("代理级风控上限与命中记录（P1-C1）")
class CenterAgentRiskLimitTest {

    private static final String AGENT_CODE = "RISK_LIMIT_AGENT";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CenterOrderService orderService;
    @Autowired
    private CenterAgentRepository agentRepository;

    private long centerAgentId;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("""
                INSERT INTO center_agent (agent_code, agent_name, upstream_account, token_hash, token_prefix,
                                          status, credit_limit, available_balance)
                VALUES (?, '风控测试代理', ?, ?, 'riskpfx', 'ACTIVE', 1000000.00, 1000000.00)
                """, AGENT_CODE, "risk_limit_upstream", "a".repeat(64));
        centerAgentId = jdbc.queryForObject(
                "SELECT id FROM center_agent WHERE agent_code = ?", Long.class, AGENT_CODE);
    }

    @Test
    @DisplayName("未配置代理级上限时沿用全局默认（与阶段 B 行为一致）")
    void fallsBackToGlobalDefaults() {
        CenterAgentRepository.AgentRow agent = agentRepository.findById(centerAgentId).orElseThrow();
        assertThat(agent.maxSingleAmount()).isNull();
        assertThat(agent.maxIssueExposure()).isNull();

        // 全局单笔上限 100000：99999 应被接受
        CenterOrderService.Accepted accepted = accept("RISK-OK-1", new BigDecimal("99999.00"));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");

        // 100001 应被拒
        CenterOrderService.Accepted rejected = accept("RISK-OK-2", new BigDecimal("100001.00"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectCode()).isEqualTo("CENTER_AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("配置代理级单笔上限后仅该代理受限")
    void perAgentSingleAmountLimitApplies() {
        agentRepository.updateRiskLimits(centerAgentId, new BigDecimal("500.00"), null);

        CenterOrderService.Accepted accepted = accept("RISK-SINGLE-1", new BigDecimal("500.00"));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");

        CenterOrderService.Accepted rejected = accept("RISK-SINGLE-2", new BigDecimal("500.01"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectCode()).isEqualTo("CENTER_AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("配置代理级单期敞口上限后按该代理累计判定")
    void perAgentIssueExposureLimitApplies() {
        agentRepository.updateRiskLimits(centerAgentId, null, new BigDecimal("1000.00"));

        assertThat(accept("RISK-EXP-1", new BigDecimal("600.00")).status()).isEqualTo("ACCEPTED");
        // 600 + 600 = 1200 > 1000，应被拒
        CenterOrderService.Accepted rejected = accept("RISK-EXP-2", new BigDecimal("600.00"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectCode()).isEqualTo("CENTER_ISSUE_EXPOSURE_EXCEEDED");
    }

    @Test
    @DisplayName("每次风控拒绝都追加命中记录，并更新代理上的最近拒绝摘要")
    void riskHitsAreRecorded() {
        agentRepository.updateRiskLimits(centerAgentId, new BigDecimal("100.00"), null);
        accept("RISK-HIT-1", new BigDecimal("200.00"));

        assertThat(agentRepository.findRecentRiskHits(centerAgentId, 10))
                .hasSize(1)
                .first()
                .satisfies(hit -> {
                    assertThat(hit.ruleCode()).isEqualTo("CENTER_AMOUNT_LIMIT_EXCEEDED");
                    assertThat(hit.ruleCategory()).isEqualTo("LIMIT");
                    assertThat(hit.observedValue()).isEqualByComparingTo("200.00");
                    assertThat(hit.thresholdValue()).isEqualByComparingTo("100.00");
                });

        CenterAgentRepository.AgentRow agent = agentRepository.findById(centerAgentId).orElseThrow();
        assertThat(agent.lastRejectCode()).isEqualTo("CENTER_AMOUNT_LIMIT_EXCEEDED");
        assertThat(agent.lastRejectAt()).isNotNull();
        assertThat(agent.rejectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("额度不足也计入风控命中，类别为 ACCOUNT")
    void availableInsufficientRecordedAsAccountCategory() {
        jdbc.update("UPDATE center_agent SET available_balance = 10.00 WHERE id = ?", centerAgentId);
        CenterOrderService.Accepted rejected = accept("RISK-AVAIL-1", new BigDecimal("50.00"));

        assertThat(rejected.rejectCode()).isEqualTo("CENTER_AVAILABLE_INSUFFICIENT");
        assertThat(agentRepository.findRecentRiskHits(centerAgentId, 10))
                .singleElement()
                .satisfies(hit -> assertThat(hit.ruleCategory()).isEqualTo("ACCOUNT"));
    }

    @Test
    @DisplayName("短时高频被拒触发异常行为规则，并留痕 BEHAVIOR")
    void abnormalBehaviorTriggersAfterRepeatedRejections() {
        // 阈值 5：先制造 5 次被拒
        agentRepository.updateRiskLimits(centerAgentId, new BigDecimal("1.00"), null);
        for (int i = 0; i < 5; i++) {
            accept("RISK-BEHAVIOR-" + i, new BigDecimal("100.00"));
        }
        assertThat(agentRepository.countRiskHitsSince(centerAgentId, Instant.now().minusSeconds(600)))
                .isEqualTo(5);

        // 第 6 次：命中数已达阈值，行为规则生效（额度充足也不会被受理）
        CenterOrderService.Accepted rejected = accept("RISK-BEHAVIOR-FINAL", new BigDecimal("0.50"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectCode()).isEqualTo("CENTER_BEHAVIOR_RATE_ANOMALY");
    }

    @Test
    @DisplayName("按规则聚合统计可用于管理端风险页")
    void ruleAggregationWorks() {
        agentRepository.updateRiskLimits(centerAgentId, new BigDecimal("10.00"), null);
        accept("RISK-AGG-1", new BigDecimal("50.00"));
        accept("RISK-AGG-2", new BigDecimal("60.00"));

        assertThat(agentRepository.countRiskHitsByRule(Instant.now().minusSeconds(600)))
                .anySatisfy(count -> {
                    assertThat(count.ruleCode()).isEqualTo("CENTER_AMOUNT_LIMIT_EXCEEDED");
                    assertThat(count.hitCount()).isEqualTo(2);
                    assertThat(count.agentCount()).isEqualTo(1);
                });
    }

    private CenterOrderService.Accepted accept(String orderNo, BigDecimal amount) {
        return orderService.accept(centerAgentId, orderNo, null, null, "AU8", "39999501",
                "FAN", "1", new BigDecimal("3.850"), amount);
    }

    private void clean() {
        jdbc.update("DELETE FROM center_order_disposition WHERE center_order_id IN "
                + "(SELECT id FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?))", AGENT_CODE);
        jdbc.update("DELETE FROM center_agent_ledger WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", AGENT_CODE);
        jdbc.update("DELETE FROM center_order WHERE center_agent_id IN "
                + "(SELECT id FROM center_agent WHERE agent_code = ?)", AGENT_CODE);
        // center_risk_hit 随 center_agent 级联删除
        jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", AGENT_CODE);
    }

    @AfterEach
    void tearDown() {
        clean();
    }
}
