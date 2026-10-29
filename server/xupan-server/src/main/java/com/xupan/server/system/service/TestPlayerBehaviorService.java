package com.xupan.server.system.service;

import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.BetTextParser;
import com.xupan.server.game.service.GameBettingConfigService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.game.domain.WalletOperationResult;
import com.xupan.server.game.service.自动轮期服务;
import com.xupan.server.system.domain.TestPlayerBehaviorMode;
import com.xupan.server.system.repository.PlayerDeskRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 托的自动行为：按机器和期号选择自动托、生成加权下注动作，并独立派发上下分与查询行为。
 * 所有注单、消息和积分流水均通过玩家正式业务链路产生；每个持久化动作带有执行状态与幂等标识。
 */
@Service
public class TestPlayerBehaviorService {

    /** 期初缓冲，避免所有托抢在开盘瞬间下注；末尾不再留缓冲，最后 30 秒仍属正常活动范围。 */
    private static final Duration SCHEDULE_LEAD = Duration.ofSeconds(3);
    private static final Duration SCHEDULE_TAIL = Duration.ofSeconds(1);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] ACTIVITY_TYPES = {
            "UP", "BET", "DOWN", "BET", "UP", "TURNOVER", "BET", "BALANCE",
            "DOWN", "BET", "HISTORY", "TURNOVER", "BET", "UP"
    };

    private final JdbcTemplate jdbc;
    private final PlayerDeskRepository repository;
    private final GameDataRepository gameRepository;
    private final ChatMessageService chatMessageService;
    private final GameBettingConfigService bettingConfigService;
    private final VirtualWalletService walletService;

    public TestPlayerBehaviorService(JdbcTemplate jdbc, PlayerDeskRepository repository,
                                     GameDataRepository gameRepository, ChatMessageService chatMessageService,
                                     GameBettingConfigService bettingConfigService,
                                     VirtualWalletService walletService) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.gameRepository = gameRepository;
        this.chatMessageService = chatMessageService;
        this.bettingConfigService = bettingConfigService;
        this.walletService = walletService;
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
        if (issue == null) {
            skipOtherIssues(userId, null);
            return List.of();
        }
        skipOtherIssues(userId, issue.issueNumber());
        if (!自动轮期服务.BETTING.equals(issue.phase())) {
            skipIssueActions(userId, issue.issueNumber(), "ISSUE_CLOSED");
            return List.of();
        }

        String notWorkable = workabilityFailure(userId);
        if (notWorkable != null) {
            skipIssueActions(userId, issue.issueNumber(), notWorkable);
            return List.of();
        }

        ensureIssuePlan(behavior, player, issue, force);
        return executeDueActions(userId, issue.issueNumber(), force);
    }

    /** One BY220-style automatic bet per selected bot and issue, with no per-bot count/activity controls. */
    private void ensureIssuePlan(PlayerDeskRepository.Behavior behavior, PlayerDeskRepository.PlayerRow player,
                                 GameDataRepository.IssueRecord issue, boolean force) {
        if (hasAction(player.userId(), issue.issueNumber(), "BET_TEXT")) return;
        Long agentId = agentId(player.userId());
        if (agentId == null) return;
        if (!force && !selectedForIssue(eligibleBots(agentId), player.userId(), agentId, issue.issueNumber())) return;
        BigDecimal stake = randomIntegerBetween(behavior.stakeMin(), behavior.stakeMax());
        String text = BotBetTextGenerator.by220AutomaticBet(stake);
        Instant now = Instant.now();
        createAction(behavior.id(), player.userId(), player.accountId(), issue.issueNumber(), 1,
                "BET_TEXT", text, scheduleTimes(1, issue, now, force).get(0));
    }

    /** BY220 selected without replacement up to 90 percent of each machine's automatic bot set. */
    private boolean selectedForIssue(List<Long> candidates, long userId, long agentId, String issueNumber) {
        if (candidates.isEmpty()) return false;
        List<Long> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, new java.util.Random((((long) agentId) << 32) ^ issueNumber.hashCode()));
        int limit = Math.max(1, (int) Math.ceil(shuffled.size() * 0.9d));
        return shuffled.subList(0, Math.min(limit, shuffled.size())).contains(userId);
    }

    private List<Long> eligibleBots(long agentId) {
        return jdbc.queryForList("""
                SELECT u.id FROM sys_user u
                JOIN demo_user_account a ON a.sys_user_id=u.id
                JOIN test_player_behavior b ON b.account_id=a.id
                JOIN agent ag ON ag.id=a.agent_id
                WHERE a.agent_id=? AND a.player_kind='BOT'
                  AND u.status='ACTIVE' AND a.status='ACTIVE'
                  AND b.run_mode='AUTOMATIC' AND b.enabled=TRUE
                  AND ag.status='ACTIVE'
                  AND ag.manual_betting_open=TRUE
                  AND (ag.expires_at IS NULL OR ag.expires_at>CURRENT_TIMESTAMP(6))
                  AND EXISTS (SELECT 1 FROM player_access_link l
                               WHERE l.user_id=u.id AND l.scope='PLAYER_FULL'
                                 AND l.revoked_at IS NULL AND l.expires_at>CURRENT_TIMESTAMP(6))
                ORDER BY u.id
                """, Long.class, agentId);
    }

    private Long agentId(long userId) {
        return jdbc.query("SELECT agent_id FROM demo_user_account WHERE sys_user_id=?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, userId);
    }

    private BigDecimal randomIntegerBetween(BigDecimal min, BigDecimal max) {
        int low = min.setScale(0, RoundingMode.DOWN).intValueExact();
        int high = max.setScale(0, RoundingMode.DOWN).intValueExact();
        if (high <= low) return BigDecimal.valueOf(low);
        return BigDecimal.valueOf(low + RANDOM.nextInt(high - low + 1));
    }

    private boolean hasAction(long userId, String issueNumber, String actionType) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM test_player_action "
                + "WHERE user_id=? AND issue_number=? AND action_type=?", Integer.class,
                userId, issueNumber, actionType);
        return count != null && count > 0;
    }

    /** BY220's periodic action lottery runs independently from the once-per-issue bet planner. */
    @Transactional
    public void dispatchPeriodicActivity(long userId) {
        PlayerDeskRepository.PlayerRow player = repository.findByUserId(userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在"));
        if (!"BOT".equals(player.playerKind())) return;
        PlayerDeskRepository.Behavior behavior = repository.findBehavior(userId)
                .orElseGet(() -> repository.ensureBehavior(player.accountId()));
        if (!TestPlayerBehaviorMode.AUTOMATIC.name().equals(behavior.mode())) return;
        GameDataRepository.IssueRecord issue = gameRepository.findCurrentIssue().orElse(null);
        if (issue == null || !自动轮期服务.BETTING.equals(issue.phase()) || workabilityFailure(userId) != null) return;

        String action = ACTIVITY_TYPES[RANDOM.nextInt(ACTIVITY_TYPES.length)];
        Instant now = Instant.now();
        int slot = (int) (now.getEpochSecond() / 15 % 100000);
        if ("UP".equals(action)) {
            BigDecimal balance = balance(player.accountId());
            if (balance.compareTo(behavior.stakeMin()) >= 0 || hasAction(userId, issue.issueNumber(), "AUTO_UP")) return;
            int[] amounts = {500, 7000, 1800, 800, 3000, 1500, 8000, 1000, 10000, 5000, 2500, 6000, 2000};
            int amount = amounts[RANDOM.nextInt(amounts.length)];
            PlayerDeskRepository.ActionRow row = createAction(behavior.id(), userId, player.accountId(),
                    issue.issueNumber(), 200 + slot, "AUTO_UP", Integer.toString(amount), now);
            execute(row.id());
        } else if ("DOWN".equals(action)) {
            BigDecimal balance = balance(player.accountId());
            if (balance.compareTo(new BigDecimal("2000")) <= 0 || hasAction(userId, issue.issueNumber(), "AUTO_DOWN")
                    || hasRecentAutomaticDown(userId, now.minus(Duration.ofMinutes(30)))) return;
            BigDecimal amount = balance.divide(new BigDecimal("2"), 0, RoundingMode.HALF_UP)
                    .divide(new BigDecimal("1000"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("1000"));
            if (amount.signum() <= 0 || amount.compareTo(balance) >= 0) return;
            PlayerDeskRepository.ActionRow row = createAction(behavior.id(), userId, player.accountId(),
                    issue.issueNumber(), 200 + slot, "AUTO_DOWN", amount.toPlainString(), now);
            execute(row.id());
        } else if ("BALANCE".equals(action) || "TURNOVER".equals(action) || "HISTORY".equals(action)) {
            String command = switch (action) {
                case "BALANCE" -> "查";
                case "TURNOVER" -> "流水";
                default -> "历史";
            };
            PlayerDeskRepository.ActionRow row = createAction(behavior.id(), userId, player.accountId(),
                    issue.issueNumber(), 200 + slot, "AUTO_QUERY", command, now);
            execute(row.id());
        }
        // BY220's weighted selector also contains BET slots; issue betting is dispatched by the issue planner.
    }

    private boolean hasRecentAutomaticDown(long userId, Instant since) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM test_player_action WHERE user_id=? "
                + "AND action_type='AUTO_DOWN' AND status='SUCCEEDED' AND updated_at>=?", Integer.class,
                userId, java.sql.Timestamp.from(since));
        return count != null && count > 0;
    }

    private List<Instant> scheduleTimes(int total, GameDataRepository.IssueRecord issue, Instant now, boolean force) {
        List<Instant> times = new ArrayList<>(Math.max(total, 0));
        if (total <= 0) return times;
        if (force) {
            for (int index = 0; index < total; index++) times.add(now);
            return times;
        }
        Instant start = issue.startedAt() == null ? now : issue.startedAt().isAfter(now) ? issue.startedAt() : now;
        Instant from = start.plus(SCHEDULE_LEAD);
        Instant end = issue.bettingEndsAt();
        Instant to = end == null ? from : end.minus(SCHEDULE_TAIL);
        if (to.isBefore(from)) to = from;
        long span = Math.max(0, Duration.between(from, to).toMillis());
        List<Long> offsets = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            offsets.add(span == 0 ? 0L : (long) (RANDOM.nextDouble() * span));
        }
        offsets.sort(Long::compareTo);
        for (Long offset : offsets) times.add(from.plusMillis(offset));
        return times;
    }

    private List<PlayerDeskRepository.ActionRow> executeDueActions(long userId, String issueNumber, boolean force) {
        String dueClause = force ? "" : " AND (scheduled_at IS NULL OR scheduled_at <= CURRENT_TIMESTAMP(6))";
        List<Long> ids = jdbc.queryForList("SELECT id FROM test_player_action WHERE user_id=? "
                        + "AND issue_number=? AND status IN ('PENDING','FAILED')"
                        + dueClause + " ORDER BY action_no", Long.class, userId, issueNumber);
        List<PlayerDeskRepository.ActionRow> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            result.add(execute(id));
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
            Long userId = jdbc.queryForObject("SELECT user_id FROM test_player_action WHERE id=?", Long.class, actionId);
            String notWorkable = workabilityFailure(userId);
            if (notWorkable != null) {
                return skipAction(actionId, notWorkable, skipMessage(notWorkable));
            }
            if ("BET_TEXT".equals(action.actionType()) && !machineBettingOpen(userId)) {
                return skipAction(actionId, "MACHINE_MANUALLY_CLOSED", skipMessage("MACHINE_MANUALLY_CLOSED"));
            }
            String idempotencyKey = jdbc.queryForObject("SELECT idempotency_key FROM test_player_action WHERE id=?", String.class, actionId);
            if ("AUTO_UP".equals(action.actionType()) || "AUTO_DOWN".equals(action.actionType())) {
                return executeAutomaticPoints(actionId, userId, action);
            }
            if ("AUTO_QUERY".equals(action.actionType())) {
                return executeAutomaticQuery(actionId, userId, action, idempotencyKey);
            }
            if ("BET_TEXT".equals(action.actionType())) return executeBet(actionId, userId, action, idempotencyKey);
            return skipAction(actionId, "LEGACY_ACTION", "旧托行为动作已停用");
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE test_player_action SET status='FAILED', error_code=?, error_message=?, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    "ACTION_EXECUTION_FAILED", safe(exception.getMessage()), actionId);
        }
        return loadAction(actionId);
    }

    private PlayerDeskRepository.ActionRow executeBet(long actionId, long userId,
                                                       PlayerDeskRepository.ActionRow action, String idempotencyKey) {
        BetTextParser.ParseResult parsed = BetTextParser.parse(action.sourceText());
        if (!parsed.accepted() || parsed.bets().size() != 1) {
            return skipAction(actionId, "INVALID_BET_TEXT", "生成的托下注文本无法解析");
        }
        BetTextParser.ParsedBet bet = parsed.bets().get(0);
        BigDecimal executable = executableStake(userId, action.issueNumber(), bet.playType(), bet.stake());
        if (executable == null) {
            return skipAction(actionId, "INSUFFICIENT_BALANCE_OR_LIMIT", "余额或玩法限额不允许本次下注");
        }
        if (!machineBettingOpen(userId)) {
            return skipAction(actionId, "MACHINE_MANUALLY_CLOSED", skipMessage("MACHINE_MANUALLY_CLOSED"));
        }
        ChatMessageService.ChatMessageSendOutcome outcome = chatMessageService.sendUserMessageWithOutcome(
                userId, "main", idempotencyKey, action.sourceText(), Instant.now());
        Long betId = jdbc.query("SELECT id FROM game_bet WHERE request_idempotency_key=?",
                rs -> rs.next() ? rs.getLong(1) : null, "CHAT-" + idempotencyKey);
        if (betId == null) {
            if (!machineBettingOpen(userId)) {
                return skipAction(actionId, "MACHINE_MANUALLY_CLOSED", skipMessage("MACHINE_MANUALLY_CLOSED"));
            }
            jdbc.update("UPDATE test_player_action SET status='SKIPPED', message_id=?, bet_id=NULL, error_code=?, error_message=?, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    outcome.message().id(), "LIMIT_EXCEEDED", "注单被封盘或限额拦截", actionId);
            return loadAction(actionId);
        }
        jdbc.update("UPDATE test_player_action SET status='SUCCEEDED', message_id=?, bet_id=?, error_code=NULL, error_message=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                outcome.message().id(), betId, actionId);
        return loadAction(actionId);
    }

    private PlayerDeskRepository.ActionRow executeAutomaticPoints(long actionId, long userId,
                                                                   PlayerDeskRepository.ActionRow action) {
        BigDecimal amount = new BigDecimal(action.sourceText());
        long operatorUserId = machineOperatorUserId(userId);
        long agentId = machineId(userId);
        String name = displayName(userId);
        WalletOperationResult operation;
        if ("AUTO_UP".equals(action.actionType())) {
            operation = walletService.grant(operatorUserId, userId, amount, "托自动上分：" + name,
                    "AUTO-UP-" + actionId);
            chatMessageService.publishRobotNotice("main", action.issueNumber(), "BOT-AUTO-UP-DONE-" + actionId,
                    "@" + name + ", 成功上分" + money(amount) + ", 剩" + money(operation.wallet().balance()), agentId, Instant.now());
        } else {
            operation = walletService.adjust(operatorUserId, userId, amount.negate(), "托自动下分：" + name,
                    "AUTO-DOWN-" + actionId);
            chatMessageService.publishRobotNotice("main", action.issueNumber(), "BOT-AUTO-DOWN-DONE-" + actionId,
                    "@" + name + ", 成功下分" + money(amount) + ", 剩" + money(operation.wallet().balance()), agentId, Instant.now());
        }
        jdbc.update("UPDATE test_player_action SET status='SUCCEEDED', error_code=NULL, error_message=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", actionId);
        return loadAction(actionId);
    }

    private PlayerDeskRepository.ActionRow executeAutomaticQuery(long actionId, long userId,
                                                                  PlayerDeskRepository.ActionRow action,
                                                                  String idempotencyKey) {
        ChatMessageService.ChatMessageSendOutcome outcome = chatMessageService.sendUserMessageWithOutcome(
                userId, "main", idempotencyKey, action.sourceText(), Instant.now());
        jdbc.update("UPDATE test_player_action SET status='SUCCEEDED', message_id=?, error_code=NULL, error_message=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                outcome.message().id(), actionId);
        return loadAction(actionId);
    }

    private static String money(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private long machineOperatorUserId(long userId) {
        Long operator = jdbc.queryForObject("SELECT COALESCE(ag.account_user_id,ag.created_by) "
                + "FROM demo_user_account a JOIN agent ag ON ag.id=a.agent_id WHERE a.sys_user_id=?", Long.class, userId);
        if (operator == null) throw BusinessException.conflict("BOT_MACHINE_OPERATOR_MISSING", "托所属机器账号不可用");
        return operator;
    }

    private long machineId(long userId) {
        Long id = jdbc.queryForObject("SELECT agent_id FROM demo_user_account WHERE sys_user_id=?", Long.class, userId);
        if (id == null) throw BusinessException.conflict("BOT_MACHINE_MISSING", "托未归属机器");
        return id;
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=?", String.class, userId);
    }

    private BigDecimal executableStake(long userId, String issueNumber, PlayType playType, BigDecimal planned) {
        AccountState state = jdbc.query("SELECT id,balance FROM demo_user_account WHERE sys_user_id=?",
                (rs,n) -> new AccountState(rs.getLong("id"),rs.getBigDecimal("balance")),userId)
                .stream().findFirst().orElse(null);
        if (state == null || state.balance() == null || state.balance().compareTo(planned) <= 0) return null;
        var usage = bettingConfigService.loadUsage(state.accountId(), issueNumber);
        return bettingConfigService.evaluate(playType, planned, usage) == null ? planned : null;
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id=?", BigDecimal.class, accountId);
    }

    private boolean machineBettingOpen(long userId) {
        List<Boolean> values = jdbc.queryForList("""
                SELECT ag.manual_betting_open
                  FROM demo_user_account a
                  JOIN agent ag ON ag.id=a.agent_id
                 WHERE a.sys_user_id=?
                """, Boolean.class, userId);
        return !values.isEmpty() && Boolean.TRUE.equals(values.get(0));
    }

    /** Returns the BY220-equivalent stop reason for a bot action. */
    private String workabilityFailure(long userId) {
        List<Workability> rows = jdbc.query("""
                SELECT u.status user_status,a.status account_status,ag.status machine_status,
                       ag.expires_at,
                       (SELECT COUNT(*) FROM player_access_link l WHERE l.user_id=u.id AND l.scope='PLAYER_FULL'
                        AND l.revoked_at IS NULL AND l.expires_at>CURRENT_TIMESTAMP(6)) usable_links
                  FROM sys_user u JOIN demo_user_account a ON a.sys_user_id=u.id
                  LEFT JOIN agent ag ON ag.id=a.agent_id
                 WHERE u.id=? AND a.player_kind='BOT'
                """, (rs,n) -> new Workability(rs.getString("user_status"),rs.getString("account_status"),
                rs.getString("machine_status"),rs.getTimestamp("expires_at"),
                rs.getInt("usable_links")), userId);
        if (rows.isEmpty()) return "PLAYER_INACTIVE";
        Workability state=rows.get(0);
        if (!"ACTIVE".equals(state.userStatus()) || !"ACTIVE".equals(state.accountStatus())) return "PLAYER_INACTIVE";
        if (!"ACTIVE".equals(state.machineStatus())
                || state.machineExpiresAt()!=null && state.machineExpiresAt().toInstant().isBefore(Instant.now())) return "MACHINE_CLOSED";
        return state.usableLinks()>0 ? null : "LINK_INVALID";
    }

    private void skipOtherIssues(long userId, String currentIssueNumber) {
        jdbc.update("UPDATE test_player_action SET status='SKIPPED', error_code='ISSUE_CLOSED', "
                        + "error_message='期号已结束，动作不再执行', lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE user_id=? AND status IN ('PENDING','FAILED') "
                        + "AND (issue_number IS NULL OR issue_number <> ?)",
                userId, currentIssueNumber == null ? "" : currentIssueNumber);
    }

    private void skipIssueActions(long userId, String issueNumber, String reasonCode) {
        jdbc.update("UPDATE test_player_action SET status='SKIPPED', error_code=?, error_message=?, "
                        + "lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE user_id=? AND issue_number=? AND status IN ('PENDING','FAILED')",
                reasonCode, skipMessage(reasonCode), userId, issueNumber);
    }

    private PlayerDeskRepository.ActionRow skipAction(long actionId, String reasonCode, String message) {
        jdbc.update("UPDATE test_player_action SET status='SKIPPED', error_code=?, error_message=?, "
                        + "lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                reasonCode, message, actionId);
        return loadAction(actionId);
    }

    private static String skipMessage(String reasonCode) {
        return switch (reasonCode) {
            case "LINK_INVALID" -> "链接已过期或已拉黑，托停止工作";
            case "PLAYER_INACTIVE" -> "玩家已停用或删除";
            case "ISSUE_CLOSED" -> "期号已封盘或开奖，动作不再执行";
            case "MACHINE_CLOSED" -> "机器已停用、闭盘或到期，托停止工作";
            case "MACHINE_MANUALLY_CLOSED" -> "机器已手动封盘，托下注动作不再执行";
            default -> reasonCode;
        };
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
            jdbc.update("INSERT INTO test_player_action(behavior_id,user_id,account_id,action_no,action_type,idempotency_key,source_text,scheduled_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
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
                                                        int actionNo, String type, String text, Instant scheduledAt) {
        String key = "TEST-PLAYER-" + userId + "-" + issue + "-" + type + "-" + actionNo;
        try {
            jdbc.update("INSERT INTO test_player_action(behavior_id,user_id,account_id,issue_number,action_no,action_type,idempotency_key,source_text,scheduled_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    behaviorId, userId, accountId, issue, actionNo, type, key, text,
                    java.sql.Timestamp.from(scheduledAt));
        } catch (DuplicateKeyException ignored) {
            // 同槽位动作已经创建过，保持原计划。
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

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "未提供错误信息";
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private record Workability(String userStatus, String accountStatus, String machineStatus, java.sql.Timestamp machineExpiresAt, int usableLinks) {
    }

    private record AccountState(long accountId, BigDecimal balance) {
    }
}
