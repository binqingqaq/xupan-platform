package com.xupan.server.system.service;

import com.xupan.server.config.ScheduledTaskLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分层保留策略的数据库清理任务。每次只在短事务内删除有限批次。 */
@Component
public final class DataRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
    private static final String SUPPORTED_ROBOT_EVENTS = "('ISSUE_STARTED', 'BETTING_WARNING', 'BETTING_CLOSED', 'DRAW_RESULT')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ScheduledTaskLock taskLock;
    private final boolean enabled;
    private final int batchSize;
    private final int maxBatchesPerTable;
    private final int chatDays;
    private final int loginDays;
    private final int operationDays;
    private final int ledgerDays;

    public DataRetentionScheduler(JdbcTemplate jdbc, TransactionTemplate transactions,
                                  ScheduledTaskLock taskLock,
                                  @Value("${xupan.retention.enabled:true}") boolean enabled,
                                  @Value("${xupan.retention.batch-size:500}") int batchSize,
                                  @Value("${xupan.retention.max-batches-per-table-per-run:20}") int maxBatchesPerTable,
                                  @Value("${xupan.retention.chat-days:5}") int chatDays,
                                  @Value("${xupan.retention.login-days:180}") int loginDays,
                                  @Value("${xupan.retention.operation-days:365}") int operationDays,
                                  @Value("${xupan.retention.ledger-days:1095}") int ledgerDays) {
        if (jdbc == null || transactions == null || taskLock == null
                || batchSize < 1 || batchSize > 5000
                || maxBatchesPerTable < 1 || maxBatchesPerTable > 1000
                || chatDays < 1 || loginDays < 1 || operationDays < 1 || ledgerDays < 1) {
            throw new IllegalArgumentException("数据保留清理参数无效");
        }
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.taskLock = taskLock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxBatchesPerTable = maxBatchesPerTable;
        this.chatDays = chatDays;
        this.loginDays = loginDays;
        this.operationDays = operationDays;
        this.ledgerDays = ledgerDays;
    }

    @Scheduled(cron = "${xupan.retention.cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void cleanExpiredData() {
        if (!enabled) {
            return;
        }
        try {
            taskLock.runExclusively("xupan:data-retention", () -> {
                CleanupResult result = cleanAt(Instant.now());
                if (result.deletedRows() > 0) {
                    log.info("数据保留清理完成 deletedRows={} tables={}", result.deletedRows(), result.deletedByTable());
                }
            });
        } catch (RuntimeException exception) {
            log.error("数据保留清理失败 operation=cleanExpiredData batchSize={} maxBatchesPerTable={}",
                    batchSize, maxBatchesPerTable, exception);
        }
    }

    /** 供同包定向集成测试调用；计划任务自身会在数据库互斥锁内调用此方法。 */
    CleanupResult cleanAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("清理参考时间不能为空");
        }
        Map<String, Integer> deleted = new LinkedHashMap<>();
        Instant chatCutoff = cutoff(now, chatDays);
        Instant loginCutoff = cutoff(now, loginDays);
        Instant operationCutoff = cutoff(now, operationDays);
        Instant ledgerCutoff = cutoff(now, ledgerDays);

        deleted.put("test_player_action", deleteBatches("test_player_action",
                "SELECT id FROM test_player_action WHERE created_at < ? AND status IN ('SUCCEEDED', 'SKIPPED') ORDER BY created_at, id LIMIT ?",
                chatCutoff));
        deleted.put("chat_robot_dispatch", deleteTerminalRobotDispatches(chatCutoff));
        deleted.put("game_issue_event", deleteBatches("game_issue_event",
                "SELECT e.id FROM game_issue_event e WHERE e.created_at < ? AND e.event_type IN "
                        + SUPPORTED_ROBOT_EVENTS + " AND NOT EXISTS (SELECT 1 FROM chat_robot_dispatch d WHERE d.game_event_id = e.id) "
                        + "ORDER BY e.created_at, e.id LIMIT ?",
                chatCutoff));

        int outboxDeleted = 0;
        int chatDeleted = 0;
        List<RoomRetention> rooms = jdbc.query(
                "SELECT id, message_retention_days FROM chat_room ORDER BY id",
                (rs, rowNum) -> new RoomRetention(rs.getLong("id"), rs.getInt("message_retention_days")));
        for (RoomRetention room : rooms) {
            Instant roomCutoff = cutoff(now, Math.max(1, room.retentionDays()));
            outboxDeleted += deleteBatches("chat_outbox",
                    "SELECT o.id FROM chat_outbox o JOIN chat_message m ON m.id = o.message_id "
                            + "WHERE o.status = 'PUBLISHED' AND o.created_at < ? AND m.room_id = ? AND m.created_at < ? "
                            + "ORDER BY o.created_at, o.id LIMIT ?",
                    roomCutoff, room.id(), roomCutoff);
            chatDeleted += deleteBatches("chat_message",
                    "SELECT m.id FROM chat_message m WHERE m.room_id = ? AND m.created_at < ? "
                            + "AND NOT EXISTS (SELECT 1 FROM chat_outbox o WHERE o.message_id = m.id) "
                            + "AND NOT EXISTS (SELECT 1 FROM chat_robot_dispatch d WHERE d.message_id = m.id) "
                            + "AND NOT EXISTS (SELECT 1 FROM test_player_action a WHERE a.message_id = m.id) "
                            + "ORDER BY m.created_at, m.id LIMIT ?",
                    room.id(), roomCutoff);
        }
        deleted.put("chat_outbox", outboxDeleted);
        deleted.put("chat_message", chatDeleted);
        deleted.put("sys_login_log", deleteBatches("sys_login_log",
                "SELECT id FROM sys_login_log WHERE created_at < ? ORDER BY created_at, id LIMIT ?",
                loginCutoff));
        deleted.put("sys_operation_log", deleteBatches("sys_operation_log",
                "SELECT id FROM sys_operation_log WHERE created_at < ? ORDER BY created_at, id LIMIT ?",
                operationCutoff));

        BetCleanupCounts betCleanup = deleteSettledBets(ledgerCutoff);
        deleted.put("game_bet", betCleanup.bets());
        deleted.put("game_bet_edit_record", betCleanup.editRecords());
        deleted.put("demo_balance_ledger", betCleanup.ledgerEntries() + deleteBatches("demo_balance_ledger",
                "SELECT id FROM demo_balance_ledger WHERE created_at < ? AND related_bet_id IS NULL ORDER BY created_at, id LIMIT ?",
                ledgerCutoff));
        deleted.put("agent_ledger", deleteBatches("agent_ledger",
                "SELECT id FROM agent_ledger WHERE created_at < ? ORDER BY created_at, id LIMIT ?",
                ledgerCutoff));
        deleted.put("center_agent_ledger", deleteBatches("center_agent_ledger",
                "SELECT id FROM center_agent_ledger WHERE created_at < ? "
                        + "AND (reference_type IS NULL OR reference_type <> 'CENTER_ORDER') "
                        + "ORDER BY created_at, id LIMIT ?",
                ledgerCutoff));
        return new CleanupResult(deleted);
    }

    private int deleteTerminalRobotDispatches(Instant cutoff) {
        String select = "SELECT id FROM chat_robot_dispatch WHERE created_at < ? "
                + "AND status IN ('PUBLISHED', 'SKIPPED') ORDER BY created_at, id LIMIT ?";
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerTable; batch++) {
            Integer count = transactions.execute(status -> {
                List<Long> ids = selectIds(select, cutoff);
                if (ids.isEmpty()) {
                    return 0;
                }
                String placeholders = placeholders(ids.size());
                jdbc.update("UPDATE chat_robot_dispatch SET message_id = NULL WHERE id IN (" + placeholders + ")",
                        ids.toArray());
                return jdbc.update("DELETE FROM chat_robot_dispatch WHERE id IN (" + placeholders + ")", ids.toArray());
            });
            int deleted = count == null ? 0 : count;
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    private BetCleanupCounts deleteSettledBets(Instant cutoff) {
        String select = """
                SELECT b.id
                  FROM game_bet b
                 WHERE b.settlement_status IN ('WIN', 'DRAW', 'LOSE')
                   AND b.settled_at IS NOT NULL AND b.settled_at < ?
                   AND NOT EXISTS (SELECT 1 FROM report_upstream_order u WHERE u.bet_id = b.id)
                   AND NOT EXISTS (SELECT 1 FROM center_order c WHERE c.local_bet_id = b.id)
                   AND NOT EXISTS (SELECT 1 FROM center_reconciliation_disposition r WHERE r.bet_id = b.id)
                   AND NOT EXISTS (SELECT 1 FROM game_bet_edit_record e WHERE e.bet_id = b.id AND e.created_at >= ?)
                   AND NOT EXISTS (SELECT 1 FROM demo_balance_ledger l WHERE l.related_bet_id = b.id AND l.created_at >= ?)
                   AND NOT EXISTS (SELECT 1 FROM test_player_action a WHERE a.bet_id = b.id)
                 ORDER BY b.settled_at, b.id
                 LIMIT ?
                """;
        int bets = 0;
        int edits = 0;
        int ledgers = 0;
        for (int batch = 0; batch < maxBatchesPerTable; batch++) {
            BetBatchCounts count = transactions.execute(status -> {
                List<Long> ids = selectIds(select, cutoff, cutoff, cutoff);
                if (ids.isEmpty()) {
                    return new BetBatchCounts(0, 0, 0);
                }
                String in = placeholders(ids.size());
                Object[] params = ids.toArray();
                int deletedEdits = jdbc.update("DELETE FROM game_bet_edit_record WHERE bet_id IN (" + in + ")", params);
                int deletedLedgers = jdbc.update("DELETE FROM demo_balance_ledger WHERE related_bet_id IN (" + in + ")", params);
                int deletedBets = jdbc.update("DELETE FROM game_bet WHERE id IN (" + in + ")", params);
                return new BetBatchCounts(deletedBets, deletedEdits, deletedLedgers);
            });
            BetBatchCounts batchCounts = count == null ? new BetBatchCounts(0, 0, 0) : count;
            bets += batchCounts.bets();
            edits += batchCounts.editRecords();
            ledgers += batchCounts.ledgerEntries();
            if (batchCounts.bets() < batchSize) {
                break;
            }
        }
        return new BetCleanupCounts(bets, edits, ledgers);
    }

    private int deleteBatches(String table, String selectSql, Object... selectParameters) {
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerTable; batch++) {
            Integer count = transactions.execute(status -> {
                List<Long> ids = selectIds(selectSql, selectParameters);
                if (ids.isEmpty()) {
                    return 0;
                }
                return jdbc.update("DELETE FROM " + table + " WHERE id IN (" + placeholders(ids.size()) + ")",
                        ids.toArray());
            });
            int deleted = count == null ? 0 : count;
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    private List<Long> selectIds(String sql, Object... parameters) {
        List<Object> arguments = new ArrayList<>(List.of(parameters));
        arguments.add(batchSize);
        return jdbc.queryForList(sql, Long.class, arguments.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Instant cutoff(Instant now, int days) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    private record BetBatchCounts(int bets, int editRecords, int ledgerEntries) {
    }

    private record BetCleanupCounts(int bets, int editRecords, int ledgerEntries) {
    }

    private record RoomRetention(long id, int retentionDays) {
    }

    public record CleanupResult(Map<String, Integer> deletedByTable) {
        public CleanupResult {
            deletedByTable = Collections.unmodifiableMap(new LinkedHashMap<>(deletedByTable));
        }

        public int deletedRows() {
            return deletedByTable.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
