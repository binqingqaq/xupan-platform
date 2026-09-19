package com.xupan.server.system.service;

import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.自动轮期服务;
import com.xupan.server.system.repository.PlayerDeskRepository;
import com.xupan.server.system.domain.TestPlayerBehaviorMode;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class TestPlayerBehaviorService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PlayerDeskRepository repository;
    private final GameDataRepository gameRepository;
    private final ChatMessageService chatMessageService;

    public TestPlayerBehaviorService(JdbcTemplate jdbc, PlayerDeskRepository repository,
                                     GameDataRepository gameRepository, ChatMessageService chatMessageService) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.gameRepository = gameRepository;
        this.chatMessageService = chatMessageService;
    }

    @Transactional
    public List<PlayerDeskRepository.ActionRow> dispatch(long userId) {
        return dispatch(userId, false);
    }

    @Transactional
    public List<PlayerDeskRepository.ActionRow> dispatch(long userId, boolean force) {
        PlayerDeskRepository.PlayerRow player = repository.findByUserId(userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在"));
        if (!"BOT".equals(player.playerKind())) {
            throw BusinessException.badRequest("PLAYER_BEHAVIOR_NOT_APPLICABLE", "普通玩家不支持托自动行为");
        }
        PlayerDeskRepository.Behavior behavior = repository.findBehavior(userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_BEHAVIOR_NOT_FOUND", "托行为配置不存在"));
        if (!force && !TestPlayerBehaviorMode.AUTOMATIC.name().equals(behavior.mode())) return List.of();
        var issue = gameRepository.findCurrentIssue().orElse(null);
        if (issue == null || !自动轮期服务.BETTING.equals(issue.phase())) return List.of();

        jdbc.update("UPDATE test_player_action SET status='PENDING', lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE user_id=? AND status='PROCESSING' AND lease_until < CURRENT_TIMESTAMP(6)", userId);
        List<PlayerDeskRepository.ActionRow> result = new ArrayList<>();
        int betCount = count(userId, issue.issueNumber(), "BET_TEXT");
        int messageCount = count(userId, issue.issueNumber(), "CHAT_TEXT");
        for (int i = betCount; i < behavior.betsPerIssue(); i++) {
            BigDecimal stake = randomStake(behavior.stakeMin(), behavior.stakeMax());
            String text = "1番" + stake.stripTrailingZeros().toPlainString();
            result.add(execute(createAction(behavior.id(), userId, player.accountId(), issue.issueNumber(), i + 1,
                    "BET_TEXT", text).id()));
        }
        for (int i = messageCount; behavior.chatEnabled() && i < behavior.messagesPerIssue(); i++) {
            result.add(execute(createAction(behavior.id(), userId, player.accountId(), issue.issueNumber(), i + 1,
                    "CHAT_TEXT", "大家好，托玩家测试消息").id()));
        }
        return result;
    }

    @Transactional
    public PlayerDeskRepository.ActionRow execute(long actionId) {
        PlayerDeskRepository.ActionRow action = loadAction(actionId);
        if ("SUCCEEDED".equals(action.status()) || "SKIPPED".equals(action.status())) return action;
        int claimed = jdbc.update("UPDATE test_player_action SET status='PROCESSING', attempts=attempts+1, lease_until=?, updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('PENDING','FAILED')",
                java.sql.Timestamp.from(Instant.now().plusSeconds(30)), actionId);
        if (claimed == 0) return loadAction(actionId);
        try {
            long userId = jdbc.queryForObject("SELECT user_id FROM test_player_action WHERE id=?", Long.class, actionId);
            String idempotencyKey = jdbc.queryForObject("SELECT idempotency_key FROM test_player_action WHERE id=?", String.class, actionId);
            ChatMessageService.ChatMessageSendOutcome outcome = chatMessageService.sendUserMessageWithOutcome(
                    userId, "main", idempotencyKey, action.sourceText(), Instant.now());
            Long betId = jdbc.query("SELECT id FROM game_bet WHERE request_idempotency_key=?", rs -> rs.next() ? rs.getLong(1) : null,
                    "CHAT-" + idempotencyKey);
            jdbc.update("UPDATE test_player_action SET status='SUCCEEDED', message_id=?, bet_id=?, error_code=NULL, error_message=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    outcome.message().id(), betId, actionId);
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE test_player_action SET status='FAILED', error_code=?, error_message=?, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    "ACTION_EXECUTION_FAILED", safe(exception.getMessage()), actionId);
        }
        return loadAction(actionId);
    }

    @Transactional
    public ChatMessageService.ChatMessageSendOutcome sendManualMessage(long userId, String clientMessageId, String content) {
        PlayerDeskRepository.PlayerRow player = repository.findByUserId(userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在"));
        if (!"BOT".equals(player.playerKind())) {
            throw BusinessException.badRequest("PLAYER_MESSAGE_NOT_APPLICABLE", "只有托可以从工作台发送测试消息");
        }
        PlayerDeskRepository.Behavior behavior = repository.findBehavior(userId)
                .orElseGet(() -> repository.ensureBehavior(player.accountId()));
        String actionKey = "MANUAL-" + clientMessageId;
        try {
            jdbc.update("INSERT INTO test_player_action(behavior_id,user_id,account_id,action_no,action_type,idempotency_key,source_text) VALUES (?,?,?,?,?,?,?)",
                    behavior.id(), userId, player.accountId(), 0, "CHAT_TEXT", actionKey, content);
        } catch (DuplicateKeyException ignored) {
        }
        PlayerDeskRepository.ActionRow action = loadActionByKey(actionKey);
        ChatMessageService.ChatMessageSendOutcome outcome = chatMessageService.sendUserMessageWithOutcome(
                userId, "main", clientMessageId, content, Instant.now());
        jdbc.update("UPDATE test_player_action SET status='SUCCEEDED', message_id=?, error_code=NULL, error_message=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                outcome.message().id(), action.id());
        return outcome;
    }

    private PlayerDeskRepository.ActionRow createAction(long behaviorId, long userId, long accountId, String issue,
                                                         int actionNo, String type, String text) {
        String key = "TEST-PLAYER-" + userId + "-" + issue + "-" + type + "-" + actionNo;
        try {
            jdbc.update("INSERT INTO test_player_action(behavior_id,user_id,account_id,issue_number,action_no,action_type,idempotency_key,source_text) VALUES (?,?,?,?,?,?,?,?)",
                    behaviorId, userId, accountId, issue, actionNo, type, key, text);
        } catch (DuplicateKeyException ignored) {
        }
        return loadActionByKey(key);
    }

    private PlayerDeskRepository.ActionRow loadAction(long actionId) {
        return jdbc.queryForObject("SELECT * FROM test_player_action WHERE id=?", this::mapAction, actionId);
    }

    private PlayerDeskRepository.ActionRow loadActionByKey(String key) {
        return jdbc.queryForObject("SELECT * FROM test_player_action WHERE idempotency_key=?", this::mapAction, key);
    }

    private PlayerDeskRepository.ActionRow mapAction(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PlayerDeskRepository.ActionRow(rs.getLong("id"), rs.getString("issue_number"),
                rs.getInt("action_no"), rs.getString("action_type"), rs.getString("source_text"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("error_code"),
                rs.getString("error_message"), (Long) rs.getObject("message_id"), (Long) rs.getObject("bet_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private int count(long userId, String issue, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM test_player_action WHERE user_id=? AND issue_number=? AND action_type=? AND status IN ('PENDING','PROCESSING','SUCCEEDED')",
                Integer.class, userId, issue, type);
    }

    private static BigDecimal randomStake(BigDecimal min, BigDecimal max) {
        if (min.compareTo(max) == 0) return min.setScale(2, RoundingMode.HALF_UP);
        BigDecimal range = max.subtract(min);
        return min.add(range.multiply(BigDecimal.valueOf(RANDOM.nextDouble()))).setScale(2, RoundingMode.HALF_UP);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "未提供错误信息";
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
