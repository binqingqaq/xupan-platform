package com.xupan.server.system.service;

import com.xupan.server.chat.repository.PlayerPointRequestRepository;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.BetTextParser;
import com.xupan.server.game.service.GameBettingConfigService;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 托的自动行为。
 *
 * <p>工作前提：账号可用并且存在未拉黑、未过期的玩家链接；链接失效期间不产生任何动作，
 * 已有待执行动作按 {@code LINK_INVALID} 收尾。
 *
 * <p>自动模式下不发送闲聊消息，只按下注计划工作：
 * 活跃比例先决定本期是否参与，再按比例收缩本期注单数；每单在投注窗口内随机时刻执行；
 * 金额按配置档位随机、可要求整十、只使用整数，并按余额和剩余额度降额。
 */
@Service
public class TestPlayerBehaviorService {

    /** 期初缓冲，避免所有托抢在开盘瞬间下注；末尾不再留缓冲，最后 30 秒仍属正常活动范围。 */
    private static final Duration SCHEDULE_LEAD = Duration.ofSeconds(3);
    private static final Duration SCHEDULE_TAIL = Duration.ofSeconds(1);
    private static final BigDecimal RANDOM_RANGE_MIN = new BigDecimal("30");
    private static final BigDecimal RANDOM_RANGE_MAX = new BigDecimal("30000");
    /** 全局随机档位下，单笔不超过当前余额的 20%，避免一下子梭哈。 */
    private static final BigDecimal RANDOM_BALANCE_RATIO = new BigDecimal("0.20");
    private static final int LIMIT_RETRY_ROUNDS = 4;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int NIGHT_END_HOUR = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PlayerDeskRepository repository;
    private final GameDataRepository gameRepository;
    private final ChatMessageService chatMessageService;
    private final GameBettingConfigService bettingConfigService;
    private final PlayerPointRequestRepository pointRequestRepository;

    public TestPlayerBehaviorService(JdbcTemplate jdbc, PlayerDeskRepository repository,
                                     GameDataRepository gameRepository, ChatMessageService chatMessageService,
                                     GameBettingConfigService bettingConfigService,
                                     PlayerPointRequestRepository pointRequestRepository) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.gameRepository = gameRepository;
        this.chatMessageService = chatMessageService;
        this.bettingConfigService = bettingConfigService;
        this.pointRequestRepository = pointRequestRepository;
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

    /**
     * 按活跃比例生成本期计划：先决定本期是否参与（A），参与时把注单数按比例收缩（B）。
     * 已存在同槽位动作时保持原计划，不重复创建。
     */
    private void ensureIssuePlan(PlayerDeskRepository.Behavior behavior, PlayerDeskRepository.PlayerRow player,
                                 GameDataRepository.IssueRecord issue, boolean force) {
        Instant now = Instant.now();
        int configured = Math.max(0, behavior.betsPerIssue());
        int activity = effectiveActivityPercent(behavior, now);
        int slots = slotCount(configured, activity, force || rollPercent(activity));
        if (slots > 0) {
            List<PlayType> allowed = allowedPlayTypes(behavior);
            List<Instant> schedule = scheduleTimes(slots, issue, now, force);
            for (int index = 0; index < slots; index++) {
                PlayType playType = allowed.get(RANDOM.nextInt(allowed.size()));
                List<Integer> parameters = BotBetTextGenerator.randomParameters(playType);
                BigDecimal planned = plannedStake(behavior, balance(player.accountId()));
                String text = BotBetTextGenerator.format(playType, parameters,
                        planned == null ? RANDOM_RANGE_MIN : planned);
                createAction(behavior.id(), player.userId(), player.accountId(), issue.issueNumber(), index + 1,
                        "BET_TEXT", text, schedule.get(index));
            }
        }
        planTopUpRequest(behavior, player, issue, now, force);
    }

    /**
     * 活跃比例的 A+B 口径：A 决定本期是否参与（由调用方先掷概率），
     * B 在参与时把本期注单数按比例收缩，至少 1 单且不超过配置值。
     */
    static int slotCount(int configured, int activityPercent, boolean participate) {
        if (configured <= 0 || activityPercent <= 0 || !participate) return 0;
        int slots = Math.max(1, (int) Math.round(configured * activityPercent / 100.0));
        return Math.min(slots, configured);
    }

    /** 随机上分申请：按概率触发，同一次申请只进后台审批队列，不在聊天室发消息。 */
    private void planTopUpRequest(PlayerDeskRepository.Behavior behavior, PlayerDeskRepository.PlayerRow player,
                                  GameDataRepository.IssueRecord issue, Instant now, boolean force) {
        int probability = behavior.topupProbabilityPercent();
        if (probability <= 0 || !rollPercent(probability)) return;
        if (hasPendingPointRequest(player.userId())) return;
        BigDecimal amount = randomBetween(behavior.topupMin(), behavior.topupMax());
        if (amount.signum() <= 0) return;
        Instant at = scheduleTimes(1, issue, now, force).get(0);
        createAction(behavior.id(), player.userId(), player.accountId(), issue.issueNumber(), 0,
                "TOP_UP_REQUEST", "上" + amount.stripTrailingZeros().toPlainString(), at);
    }

    private List<PlayType> allowedPlayTypes(PlayerDeskRepository.Behavior behavior) {
        if (behavior.playRandom()) return List.of(PlayType.values());
        List<String> codes = repository.findPlayTypes(behavior.id());
        if (codes.isEmpty()) return List.of(PlayType.values());
        return codes.stream().map(PlayType::valueOf).toList();
    }

    private int effectiveActivityPercent(PlayerDeskRepository.Behavior behavior, Instant now) {
        Integer override = bettingConfigService.get().botNightActivityOverridePercent();
        if (override != null && isNight(now)) return override;
        return behavior.activityPercent();
    }

    private static boolean isNight(Instant now) {
        ZonedDateTime local = now.atZone(BUSINESS_ZONE);
        return local.getHour() < NIGHT_END_HOUR;
    }

    /** 配置档位内随机；全局随机档位额外受“当前余额 20%”限制，并偏向较小金额。 */
    private BigDecimal plannedStake(PlayerDeskRepository.Behavior behavior, BigDecimal balance) {
        if ("RANDOM".equals(behavior.stakeRangeCode())) {
            BigDecimal max = RANDOM_RANGE_MAX;
            if (balance != null && balance.signum() > 0) {
                BigDecimal cap = balance.multiply(RANDOM_BALANCE_RATIO).setScale(0, RoundingMode.DOWN);
                if (cap.compareTo(max) < 0) max = cap;
            }
            if (max.compareTo(RANDOM_RANGE_MIN) < 0) return null;
            BigDecimal first = randomBetween(RANDOM_RANGE_MIN, max);
            BigDecimal second = randomBetween(RANDOM_RANGE_MIN, max);
            return applyRoundTen(first.min(second), behavior.stakeRoundTen());
        }
        if (behavior.stakeMax().compareTo(behavior.stakeMin()) < 0) return null;
        return applyRoundTen(randomBetween(behavior.stakeMin(), behavior.stakeMax()), behavior.stakeRoundTen());
    }

    private static BigDecimal randomBetween(BigDecimal min, BigDecimal max) {
        if (max.compareTo(min) <= 0) return min.setScale(0, RoundingMode.DOWN);
        BigDecimal range = max.subtract(min);
        BigDecimal value = min.add(range.multiply(BigDecimal.valueOf(RANDOM.nextDouble())));
        return value.setScale(0, RoundingMode.DOWN);
    }

    /** 整十=开 向下取到 10 的倍数；随机 表示每单随机决定；其余保持整数原值。 */
    private static BigDecimal applyRoundTen(BigDecimal value, String rule) {
        boolean roundTen = switch (rule == null ? "OFF" : rule) {
            case "ON" -> true;
            case "RANDOM" -> RANDOM.nextBoolean();
            default -> false;
        };
        BigDecimal result = roundTen
                ? value.divide(BigDecimal.TEN, 0, RoundingMode.DOWN).multiply(BigDecimal.TEN)
                : value;
        return result.setScale(0, RoundingMode.DOWN);
    }

    /**
     * 在投注窗口内生成排序后的随机执行时刻：每个槽位一个时刻，按顺序递增。
     */
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
            String idempotencyKey = jdbc.queryForObject("SELECT idempotency_key FROM test_player_action WHERE id=?", String.class, actionId);
            if ("TOP_UP_REQUEST".equals(action.actionType())) {
                return executeTopUpRequest(actionId, userId, action, idempotencyKey);
            }
            return executeBet(actionId, userId, action, idempotencyKey);
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
        String text = action.sourceText();
        BigDecimal stake = bet.stake();
        if (action.attempts() == 0) {
            BigDecimal executable = executableStake(userId, action.issueNumber(), bet.playType(), bet.stake());
            if (executable == null) {
                return skipAction(actionId, "INSUFFICIENT_BALANCE", "余额或剩余额度不足以完成本单");
            }
            stake = executable;
            String executableText = BotBetTextGenerator.format(bet.playType(), bet.parameters(), stake);
            if (!executableText.equals(text)) {
                jdbc.update("UPDATE test_player_action SET source_text=? WHERE id=?", executableText, actionId);
                text = executableText;
            }
        }
        if (botIssueCapReached(action.issueNumber(), stake)) {
            return skipAction(actionId, "BOT_ISSUE_CAP", "已达到所有托本期的总注单或总积分上限");
        }
        ChatMessageService.ChatMessageSendOutcome outcome = chatMessageService.sendUserMessageWithOutcome(
                userId, "main", idempotencyKey, text, Instant.now());
        Long betId = jdbc.query("SELECT id FROM game_bet WHERE request_idempotency_key=?", rs -> rs.next() ? rs.getLong(1) : null,
                "CHAT-" + idempotencyKey);
        if (betId == null) {
            jdbc.update("UPDATE test_player_action SET status='SKIPPED', message_id=?, bet_id=NULL, error_code=?, error_message=?, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    outcome.message().id(), "LIMIT_EXCEEDED", "注单被限额或封盘拦截", actionId);
            return loadAction(actionId);
        }
        jdbc.update("UPDATE test_player_action SET status='SUCCEEDED', message_id=?, bet_id=?, error_code=NULL, error_message=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                outcome.message().id(), betId, actionId);
        return loadAction(actionId);
    }

    /** 随机上分申请直接写入后台审批队列，不产生聊天消息。 */
    private PlayerDeskRepository.ActionRow executeTopUpRequest(long actionId, long userId,
                                                               PlayerDeskRepository.ActionRow action,
                                                               String idempotencyKey) {
        BigDecimal amount = topUpAmount(action.sourceText());
        if (amount == null || amount.signum() <= 0) {
            return skipAction(actionId, "INVALID_TOPUP_AMOUNT", "上分申请金额无效");
        }
        if (hasPendingPointRequest(userId)) {
            return skipAction(actionId, "TOPUP_PENDING", "已有待审批的上分申请");
        }
        pointRequestRepository.insertPending(userId, "TOP_UP", amount, idempotencyKey, 0L, Instant.now());
        jdbc.update("UPDATE test_player_action SET status='SUCCEEDED', error_code=NULL, error_message=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                actionId);
        return loadAction(actionId);
    }

    private static BigDecimal topUpAmount(String text) {
        if (text == null || !text.startsWith("上")) return null;
        try {
            return new BigDecimal(text.substring(1).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean hasPendingPointRequest(long userId) {
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM player_point_request WHERE user_id=? AND status='PENDING'",
                Integer.class, userId);
        return pending != null && pending > 0;
    }

    /**
     * 计算本单实际可下金额：计划金额依次被余额、全局随机档位的 20% 上限和剩余额度压降。
     * 压降后仍低于最小注额时返回 null，由调用方记为跳过。
     */
    private BigDecimal executableStake(long userId, String issueNumber, PlayType playType, BigDecimal planned) {
        List<AccountState> states = jdbc.query("SELECT a.id, a.balance, b.stake_range_code, b.stake_round_ten "
                        + "FROM demo_user_account a JOIN test_player_behavior b ON b.account_id=a.id "
                        + "WHERE a.sys_user_id=?",
                (rs, rowNum) -> new AccountState(rs.getLong("id"), rs.getBigDecimal("balance"),
                        rs.getString("stake_range_code"), rs.getString("stake_round_ten")), userId);
        if (states.isEmpty()) return null;
        AccountState state = states.get(0);
        if (state.balance() == null || state.balance().signum() <= 0) return null;
        BigDecimal stake = planned.setScale(0, RoundingMode.DOWN);
        if ("RANDOM".equals(state.stakeRangeCode())) {
            BigDecimal cap = state.balance().multiply(RANDOM_BALANCE_RATIO).setScale(0, RoundingMode.DOWN);
            if (stake.compareTo(cap) > 0) stake = cap;
        }
        if (stake.compareTo(state.balance()) > 0) stake = state.balance().setScale(0, RoundingMode.DOWN);
        stake = applyRoundTen(stake, state.stakeRoundTen());
        for (int round = 0; round < LIMIT_RETRY_ROUNDS; round++) {
            if (stake.signum() <= 0) return null;
            var usage = bettingConfigService.loadUsage(state.accountId(), issueNumber);
            var rejection = bettingConfigService.evaluate(playType, stake, usage);
            if (rejection == null) return stake.setScale(2, RoundingMode.DOWN);
            if (rejection.kind() == GameBettingConfigService.LimitKind.MIN
                    || rejection.remaining().signum() <= 0) {
                return null;
            }
            stake = applyRoundTen(rejection.remaining().setScale(0, RoundingMode.DOWN), state.stakeRoundTen());
        }
        return null;
    }

    /** 所有托在本期的总注单数和总积分上限；达到上限的动作直接跳过。 */
    private boolean botIssueCapReached(String issueNumber, BigDecimal stake) {
        var config = bettingConfigService.get();
        Integer bets = jdbc.queryForObject("SELECT COUNT(*) FROM game_bet b "
                        + "JOIN demo_user_account a ON a.id=b.user_id "
                        + "WHERE b.issue_number=? AND a.player_kind='BOT' AND b.settlement_status <> 'CANCELED'",
                Integer.class, issueNumber);
        BigDecimal staked = jdbc.queryForObject("SELECT COALESCE(SUM(b.stake), 0) FROM game_bet b "
                        + "JOIN demo_user_account a ON a.id=b.user_id "
                        + "WHERE b.issue_number=? AND a.player_kind='BOT' AND b.settlement_status <> 'CANCELED'",
                BigDecimal.class, issueNumber);
        if (bets != null && bets >= config.botIssueTotalBets()) return true;
        BigDecimal total = (staked == null ? BigDecimal.ZERO : staked).add(stake);
        return total.compareTo(BigDecimal.valueOf(config.botIssueTotalStake())) > 0;
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE id=?", BigDecimal.class, accountId);
    }

    private static boolean rollPercent(int percent) {
        if (percent <= 0) return false;
        if (percent >= 100) return true;
        return RANDOM.nextInt(100) < percent;
    }

    /** 返回 null 表示可工作；否则返回停止原因代码。链接是托工作的前提。 */
    private String workabilityFailure(long userId) {
        List<Workability> rows = jdbc.query("""
                SELECT u.status AS user_status, a.status AS account_status,
                       (SELECT COUNT(*) FROM player_access_link l
                         WHERE l.user_id = u.id AND l.scope = 'PLAYER_FULL'
                           AND l.revoked_at IS NULL AND l.expires_at > CURRENT_TIMESTAMP(6)) AS usable_links
                  FROM sys_user u
                  JOIN demo_user_account a ON a.sys_user_id = u.id
                 WHERE u.id = ? AND a.player_kind = 'BOT'
                """, (rs, rowNum) -> new Workability(rs.getString("user_status"),
                rs.getString("account_status"), rs.getInt("usable_links")), userId);
        if (rows.isEmpty()) return "PLAYER_INACTIVE";
        Workability state = rows.get(0);
        if (!"ACTIVE".equals(state.userStatus()) || !"ACTIVE".equals(state.accountStatus())) {
            return "PLAYER_INACTIVE";
        }
        return state.usableLinks() > 0 ? null : "LINK_INVALID";
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

    private record Workability(String userStatus, String accountStatus, int usableLinks) {
    }

    private record AccountState(long accountId, BigDecimal balance, String stakeRangeCode, String stakeRoundTen) {
    }
}
