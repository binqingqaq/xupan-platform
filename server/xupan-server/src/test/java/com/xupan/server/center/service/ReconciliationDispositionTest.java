package com.xupan.server.center.service;

import com.xupan.server.center.repository.ReconciliationDispositionRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-2 ② / C-4 ③：对账差异的处置动作与保留策略。
 *
 * <p>验证四件事：</p>
 * <ol>
 *   <li><b>处置不等于修复</b>：处置后不修改订单、不修改账本，只写一条判断记录；</li>
 *   <li>幂等：同一幂等键回放，不写第二行；</li>
 *   <li>过期语义：处置到期后差异重新回到待处置状态（避免"标一次已知就永远不再看"）；</li>
 *   <li>保留策略：撤销是状态迁移而非物理删除，判断历史可追溯。</li>
 * </ol>
 */
@SpringBootTest(properties = "xupan.automation.enabled=false")
@ActiveProfiles("test")
@DisplayName("对账差异处置与保留策略（C-2 ② / C-4 ③）")
class ReconciliationDispositionTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReconciliationDispositionService service;
    @Autowired
    private ReconciliationDispositionRepository repository;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("处置一条差异：记录结论与理由，且不影响订单与账本")
    void disposeRecordsJudgementOnly() {
        long betsBefore = count("game_bet");
        long ordersBefore = count("center_order");
        long ledgerBefore = count("center_agent_ledger");
        long agentsBefore = count("center_agent");

        ReconciliationDispositionService.DispositionResult result = service.dispose(
                "AMOUNT_MISMATCH", 12345L, "ACCEPTED", null, "已核对上游回执，金额差异为舍入",
                Duration.ofDays(7), "recon-key-1", null);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.resolution()).isEqualTo("ACCEPTED");
        assertThat(result.expiresAt()).isNotNull();

        // 关键：处置只写一条记录，绝不触碰业务数据
        assertThat(count("game_bet")).as("不得修改注单").isEqualTo(betsBefore);
        assertThat(count("center_order")).as("不得修改中心订单").isEqualTo(ordersBefore);
        assertThat(count("center_agent_ledger")).as("不得写账本").isEqualTo(ledgerBefore);
        assertThat(count("center_agent")).as("不得修改代理额度").isEqualTo(agentsBefore);
        assertThat(count("center_reconciliation_disposition")).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：同一幂等键回放首次结果，不写第二行")
    void disposeIsIdempotent() {
        ReconciliationDispositionService.DispositionResult first = service.dispose(
                "MISSING_IN_CENTER", 100L, "ACKNOWLEDGED", null, "已知悉", Duration.ofDays(7),
                "recon-key-idem", null);
        ReconciliationDispositionService.DispositionResult second = service.dispose(
                "MISSING_IN_CENTER", 100L, "IGNORED", null, "换个结论试试", Duration.ofDays(7),
                "recon-key-idem", null);

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.resolution()).as("回放首次结论，不被第二次的参数覆盖").isEqualTo("ACKNOWLEDGED");
        assertThat(count("center_reconciliation_disposition")).isEqualTo(1);
    }

    @Test
    @DisplayName("已处置的差异被识别为已处置")
    void effectivelyDisposedIsDetectable() {
        service.dispose("STATUS_MISMATCH", 200L, "FIXED", null, "已修复", Duration.ofDays(7),
                "recon-key-eff", null);

        assertThat(repository.isEffectivelyDisposed("STATUS_MISMATCH", 200L)).isTrue();
        // 同类型不同注单不受影响
        assertThat(repository.isEffectivelyDisposed("STATUS_MISMATCH", 201L)).isFalse();
        // 不同差异类型同一注单也不互相影响（唯一键是 类型+注单）
        assertThat(repository.isEffectivelyDisposed("AMOUNT_MISMATCH", 200L)).isFalse();
    }

    @Test
    @DisplayName("处置到期后差异重新回到待处置状态")
    void expiredDispositionNoLongerEffective() {
        // 用 0 时长表示"立即过期"（service 把 0/负值转成永久，因此这里直接写一条已过期的记录）
        service.dispose("AMOUNT_MISMATCH", 300L, "ACCEPTED", null, "短期接受", Duration.ofDays(7),
                "recon-key-exp", null);
        assertThat(repository.isEffectivelyDisposed("AMOUNT_MISMATCH", 300L)).isTrue();

        // 把到期时间改到过去，模拟时间流逝
        jdbc.update("UPDATE center_reconciliation_disposition SET expires_at = ? WHERE bet_id = 300",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));

        assertThat(repository.isEffectivelyDisposed("AMOUNT_MISMATCH", 300L))
                .as("过期后应重新回到待处置，避免标一次就永远不再看")
                .isFalse();
        // 记录仍在表内（不删除，供审计追溯）
        assertThat(count("center_reconciliation_disposition")).isEqualTo(1);
    }

    @Test
    @DisplayName("撤销是状态迁移而非物理删除，历史保留")
    void revokeKeepsHistory() {
        ReconciliationDispositionService.DispositionResult created = service.dispose(
                "MISSING_IN_LOCAL", 400L, "IGNORED", null, "脏数据", Duration.ofDays(7),
                "recon-key-revoke", null);

        ReconciliationDispositionService.DispositionResult revoked =
                service.revoke(created.id(), null);

        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(repository.isEffectivelyDisposed("MISSING_IN_LOCAL", 400L)).isFalse();
        // 记录仍在，且能查到撤销时间
        assertThat(repository.findHistory("MISSING_IN_LOCAL", 400L))
                .singleElement()
                .satisfies(row -> assertThat(row.revokedAt()).isNotNull());
    }

    @Test
    @DisplayName("重复撤销幂等")
    void revokeIsIdempotent() {
        ReconciliationDispositionService.DispositionResult created = service.dispose(
                "MISSING_IN_LOCAL", 500L, "IGNORED", null, "脏数据", Duration.ofDays(7),
                "recon-key-revoke2", null);
        service.revoke(created.id(), null);
        ReconciliationDispositionService.DispositionResult again = service.revoke(created.id(), null);

        assertThat(again.duplicate()).isTrue();
        assertThat(count("center_reconciliation_disposition")).isEqualTo(1);
    }

    @Test
    @DisplayName("保留策略统计生效中与已失效数量")
    void retentionStatsCountBothBuckets() {
        service.dispose("AMOUNT_MISMATCH", 600L, "ACCEPTED", null, "生效中", Duration.ofDays(7),
                "recon-key-ret-1", null);
        ReconciliationDispositionService.DispositionResult toRevoke = service.dispose(
                "AMOUNT_MISMATCH", 601L, "IGNORED", null, "将被撤销", Duration.ofDays(7),
                "recon-key-ret-2", null);
        service.revoke(toRevoke.id(), null);

        ReconciliationDispositionService.RetentionStats stats = service.retentionStats();

        assertThat(stats.effective()).isEqualTo(1);
        assertThat(stats.expired()).as("撤销过的算已失效").isEqualTo(1);
    }

    @Test
    @DisplayName("非法差异类型与结论被拒绝")
    void invalidTypeAndResolutionAreRejected() {
        assertThatThrownBy(() -> service.dispose("WHATEVER", 700L, "ACCEPTED", null, null,
                Duration.ofDays(7), "recon-key-bad-1", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_RECON_TYPE_INVALID"));

        assertThatThrownBy(() -> service.dispose("AMOUNT_MISMATCH", 700L, "WHATEVER", null, null,
                Duration.ofDays(7), "recon-key-bad-2", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_RECON_RESOLUTION_INVALID"));

        assertThatThrownBy(() -> service.dispose("AMOUNT_MISMATCH", 700L, "ACCEPTED", null, null,
                Duration.ofDays(7), "  ", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_RECON_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("生效中的处置列表可查询，已撤销的不在其中")
    void effectiveListExcludesRevoked() {
        service.dispose("AMOUNT_MISMATCH", 800L, "ACCEPTED", null, "生效", Duration.ofDays(7),
                "recon-key-list-1", null);
        ReconciliationDispositionService.DispositionResult revoked = service.dispose(
                "AMOUNT_MISMATCH", 801L, "ACCEPTED", null, "将撤销", Duration.ofDays(7),
                "recon-key-list-2", null);
        service.revoke(revoked.id(), null);

        assertThat(service.effectiveDispositions(50))
                .extracting(ReconciliationDispositionRepository.Row::betId)
                .contains(800L)
                .doesNotContain(801L);
    }

    private long count(String table) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return total == null ? 0 : total;
    }

    private void clean() {
        jdbc.update("DELETE FROM center_reconciliation_disposition WHERE bet_id BETWEEN 100 AND 99999");
    }
}
