package com.xupan.server.agent.service;

import com.xupan.server.agent.domain.Agent;
import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.WalletLedgerEntry;
import com.xupan.server.game.domain.WalletOperationResult;
import com.xupan.server.game.domain.BetCommandFormatter;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.playerauth.service.PlayerLinkAuthenticationService;
import com.xupan.server.system.repository.PlayerDeskRepository;
import com.xupan.server.system.service.TestPlayerAdminService;
import com.xupan.server.system.service.UserAdminService;
import com.xupan.server.web.BusinessException;
import com.xupan.server.web.MoneyAmounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AgentConsoleService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 单次上下分的金额上限，保持修复前既有约束。 */
    private static final BigDecimal MAX_SCORE_AMOUNT = new BigDecimal("1000000000.00");

    private final AgentRepository agentRepository;
    private final AgentLedgerService agentLedgerService;
    private final PermissionService permissionService;
    private final UserAdminService userAdminService;
    private final TestPlayerAdminService testPlayerAdminService;
    private final PlayerLinkAuthenticationService playerLinkAuthenticationService;
    private final PlayerDeskRepository playerDeskRepository;
    private final VirtualWalletService walletService;
    private final JdbcTemplate jdbc;

    public AgentConsoleService(AgentRepository agentRepository, AgentLedgerService agentLedgerService,
                                PermissionService permissionService,
                               UserAdminService userAdminService,
                               TestPlayerAdminService testPlayerAdminService,
                               PlayerLinkAuthenticationService playerLinkAuthenticationService,
                               PlayerDeskRepository playerDeskRepository,
                               VirtualWalletService walletService,
                               JdbcTemplate jdbc) {
        this.agentRepository = agentRepository;
        this.agentLedgerService = agentLedgerService;
        this.permissionService = permissionService;
        this.userAdminService = userAdminService;
        this.testPlayerAdminService = testPlayerAdminService;
        this.playerLinkAuthenticationService = playerLinkAuthenticationService;
        this.playerDeskRepository = playerDeskRepository;
        this.walletService = walletService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AgentRepository.AgentRow overview(long accountUserId) {
        requireConsole(accountUserId);
        Agent agent = requireActiveAgent(accountUserId);
        return agentRepository.findAgent(agent.id())
                .orElseThrow(() -> BusinessException.notFound("AGENT_NOT_FOUND", "代理不存在"));
    }

    @Transactional
    public AgentRepository.AgentPlayerRow createNormal(long accountUserId, String displayName) {
        requirePlayerManage(accountUserId);
        Agent agent = requireActiveAgent(accountUserId);
        long userId = userAdminService.createPlayerLinkUserForAgent(displayName, accountUserId);
        if (agentRepository.assignPlayerToAgent(userId, agent.id()) != 1) {
            throw BusinessException.conflict("AGENT_PLAYER_ASSIGN_FAILED", "玩家归属代理失败");
        }
        ensureOwnedByAgent(agent.id(), userId);
        playerLinkAuthenticationService.issueForAgent(userId, accountUserId);
        return requirePlayer(agent.id(), userId);
    }

    @Transactional
    public AgentRepository.AgentPlayerRow createBot(long accountUserId, String userCode, String displayName) {
        requirePlayerManage(accountUserId);
        Agent agent = requireActiveAgent(accountUserId);
        long currentBots = agentRepository.countPlayersByAgent(agent.id(), "BOT", null);
        long limit = agentRepository.findBotCountLimit(agent.id());
        if (currentBots >= limit) {
            throw BusinessException.conflict("AGENT_BOT_LIMIT_REACHED", "托数量已达到最大值! 创建失败！");
        }
        TestPlayerAdminService.TestPlayerAdminView view = testPlayerAdminService.createForAgent(
                userCode, displayName, accountUserId);
        long userId = view.player().userId();
        jdbc.update("""
                UPDATE demo_user_account
                   SET player_kind='BOT', agent_id=?, updated_at=CURRENT_TIMESTAMP
                 WHERE sys_user_id=?
                """, agent.id(), userId);
        jdbc.update("""
                UPDATE sys_user SET auth_mode='BOT_SERVICE', updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, userId);
        playerDeskRepository.ensureBehavior(view.player().id());
        ensureOwnedByAgent(agent.id(), userId);
        playerLinkAuthenticationService.issueForAgent(userId, accountUserId);
        return requirePlayer(agent.id(), userId);
    }

    /**
     * 机器端（{@code /console}，BY220 {@code robot.php}）上下分：**从操作者反查自己那台机器**。
     *
     * <p>外部行为、异常与幂等语义保持修复前一字不变（本方法是既有 7 条回归用例的被测对象）；
     * 池不足文案继续原文照抄 BY220（{@link AgentLedgerService#INSUFFICIENT_MACHINE_SCORE_MESSAGE}）。</p>
     */
    @Transactional
    public ScoreChange changeScore(long accountUserId, long playerUserId, String direction,
                                   BigDecimal amount, String idempotencyKey) {
        requirePlayerManage(accountUserId);
        Agent agent = requireActiveAgent(accountUserId);
        return changeScoreForAgent(accountUserId, agent.id(), playerUserId, direction, amount, idempotencyKey,
                AgentLedgerService.INSUFFICIENT_MACHINE_SCORE_MESSAGE);
    }

    /**
     * 按**显式机器 + 显式操作者**给玩家上下分（计划 §3.5「Q1=A1 的实现口径」）。
     *
     * <p>平台侧（超管 {@code GLOBAL} / 子账号 {@code GROUP}）在 {@code /platform-admin} 的玩家工作台里
     * 没有 {@code agent} 行，无法用旧的 {@link #changeScore} 反查机器，因此由调用方传入
     * **玩家归属机器**（{@code demo_user_account.agent_id}，【R1=做法①】操作者不选机器）。</p>
     *
     * <p>本方法与 {@link #changeScore} 共用同一段上下分逻辑（幂等重放校验、机器积分扣/回、
     * 钱包写入、{@code PLAYER_TOP_UP}/{@code PLAYER_DOWN} 流水、审计、托不联动），
     * 只有"操作者 → 机器"的解析留在旧方法里。**仍然**校验该玩家确实归属这台机器
     * （{@code ensureOwnedByAgent}），避免调用方传错机器造成跨机器串账。</p>
     *
     * <p>池不足文案默认走平台侧（见 {@link #changeScoreForAgent(long, long, long, String, BigDecimal, String, String)}）：
     * 被扣的是玩家归属机器，不是操作者自己的账号。</p>
     */
    @Transactional
    public ScoreChange changeScoreForAgent(long operatorUserId, long agentId, long playerUserId, String direction,
                                           BigDecimal amount, String idempotencyKey) {
        return changeScoreForAgent(operatorUserId, agentId, playerUserId, direction, amount, idempotencyKey,
                AgentLedgerService.INSUFFICIENT_OWNER_MACHINE_SCORE_MESSAGE);
    }

    /**
     * 同 {@link #changeScoreForAgent(long, long, long, String, BigDecimal, String)}，但可指定机器池不足时的文案。
     *
     * <p>错误码两条路径都是 {@code AGENT_SCORE_INSUFFICIENT}，协议不变（计划 §3.5「文案口径」）：
     * 机器端原文照抄 BY220，平台侧用"该玩家所属机器…"，避免"您的账号"被读成操作者。</p>
     */
    @Transactional
    public ScoreChange changeScoreForAgent(long operatorUserId, long agentId, long playerUserId, String direction,
                                           BigDecimal amount, String idempotencyKey, String insufficientMessage) {
        ensureOwnedByAgent(agentId, playerUserId);
        AgentRepository.AgentPlayerRow player = requirePlayer(agentId, playerUserId);
        String normalizedDirection = direction == null ? "" : direction.trim().toUpperCase(Locale.ROOT);
        if (!"TOP_UP".equals(normalizedDirection) && !"DOWN".equals(normalizedDirection)) {
            throw BusinessException.badRequest("AGENT_SCORE_DIRECTION_INVALID", "上下分方向无效");
        }
        BigDecimal normalizedAmount = positiveAmount(amount);
        String key = requiredKey(idempotencyKey);
        String reason = ("TOP_UP".equals(normalizedDirection) ? "代理上分：" : "代理下分：") + player.displayName();
        BigDecimal expectedLedgerAmount = "TOP_UP".equals(normalizedDirection)
                ? normalizedAmount : normalizedAmount.negate();

        Optional<WalletLedgerEntry> replay = walletService.findLedgerByIdempotencyKey(key);
        if (replay.isPresent()) {
            WalletLedgerEntry ledger = replay.get();
            if (ledger.accountId() != player.accountId()
                    || ledger.amount().compareTo(expectedLedgerAmount) != 0
                    || !reason.equals(ledger.reason())) {
                throw BusinessException.conflict("AGENT_SCORE_IDEMPOTENCY_CONFLICT", "上下分幂等键参数不一致");
            }
            return new ScoreChange(normalizedDirection, normalizedAmount, agentRepository.findScore(agentId),
                    ledger.balanceAfter(), ledger.id(), true);
        }

        WalletOperationResult wallet;
        if ("TOP_UP".equals(normalizedDirection)) {
            // 账本原则 2：代理积分变化必须同时写流水，且带幂等键，所以改由 AgentLedgerService 统一写。
            // 机器（BOT）玩家不扣代理积分：BY220 对托的上分不动代理余额。
            if (!"BOT".equals(player.playerKind())) {
                agentLedgerService.changeMachineScore(agentId, normalizedAmount.negate(),
                        AgentLedgerService.TYPE_PLAYER_TOP_UP, reason, "PLAYER",
                        Long.toString(playerUserId), operatorUserId, "AGENT-LEDGER-" + key, insufficientMessage);
            }
            wallet = walletService.grant(operatorUserId, playerUserId, normalizedAmount, reason, key);
        } else {
            wallet = walletService.adjust(operatorUserId, playerUserId, normalizedAmount.negate(), reason, key);
            if (!"BOT".equals(player.playerKind())) {
                agentLedgerService.changeMachineScore(agentId, normalizedAmount,
                        AgentLedgerService.TYPE_PLAYER_DOWN, reason, "PLAYER",
                        Long.toString(playerUserId), operatorUserId, "AGENT-LEDGER-" + key, insufficientMessage);
            }
        }
        BigDecimal agentScore = agentRepository.findScore(agentId);
        auditScoreChange(operatorUserId, player, normalizedDirection, normalizedAmount, agentScore,
                wallet.wallet().balance(), wallet.ledger().id());
        return new ScoreChange(normalizedDirection, normalizedAmount, agentScore,
                wallet.wallet().balance(), wallet.ledger().id(), false);
    }

    /**
     * 审批**下分申请**时的机器池结算：**只给机器池加分，不动玩家余额**。
     *
     * <p>BY220 {@code application/rbt/controller/Index.php:280-286}（{@code type != 0} 分支）：
     * 同意下分时只 {@code db('robot')->...->setInc('score', $res['score'])}（且仅当
     * {@code $res['isTuo'] == 0}，即托不联动），随后把 {@code afterScore} 记成**当时的玩家余额**——
     * **玩家余额不再变动**，因为钱已经在申请创建时被 {@code common.php:1874-1876} 扣走。
     * 因此本方法刻意不调用 {@link #changeScoreForAgent}（那条路径会再扣一次玩家钱包）。</p>
     *
     * <p>机器范围（{@code /console} 机器账号）由操作者反查自己那台机器，与
     * {@link #changeScore} 同口径、同池不足文案。</p>
     */
    @Transactional
    public ScoreChange settleApprovedDownRequest(long accountUserId, long playerUserId, BigDecimal amount,
                                                String idempotencyKey) {
        requirePlayerManage(accountUserId);
        Agent agent = requireActiveAgent(accountUserId);
        return settleApprovedDownRequestForAgent(accountUserId, agent.id(), playerUserId, amount, idempotencyKey,
                AgentLedgerService.INSUFFICIENT_MACHINE_SCORE_MESSAGE);
    }

    /**
     * 同 {@link #settleApprovedDownRequest(long, long, BigDecimal, String)}，但由调用方显式传入机器
     * （平台侧 {@code GLOBAL}/{@code GROUP} 用**玩家归属机器**，操作者不选机器）。
     */
    @Transactional
    public ScoreChange settleApprovedDownRequestForAgent(long operatorUserId, long agentId, long playerUserId,
                                                        BigDecimal amount, String idempotencyKey,
                                                        String insufficientMessage) {
        ensureOwnedByAgent(agentId, playerUserId);
        AgentRepository.AgentPlayerRow player = requirePlayer(agentId, playerUserId);
        BigDecimal normalizedAmount = positiveAmount(amount);
        String key = requiredKey(idempotencyKey);
        String reason = "代理下分：" + player.displayName();
        Long machineLedgerId = null;
        if (!"BOT".equals(player.playerKind())) {
            // 幂等键沿用与 changeScoreForAgent 完全相同的 "AGENT-LEDGER-" + key 形态，
            // 命中已有流水时按重放返回，不会重复给机器池加分。
            agentLedgerService.changeMachineScore(agentId, normalizedAmount,
                    AgentLedgerService.TYPE_PLAYER_DOWN, reason, "PLAYER",
                    Long.toString(playerUserId), operatorUserId, "AGENT-LEDGER-" + key, insufficientMessage);
            machineLedgerId = jdbc.query("SELECT id FROM agent_ledger WHERE idempotency_key = ?",
                    (rs, rowNum) -> rs.getLong(1), "AGENT-LEDGER-" + key).stream().findFirst().orElse(null);
        }
        BigDecimal agentScore = agentRepository.findScore(agentId);
        BigDecimal playerBalance = walletService.getForAdminHistory(playerUserId).balance();
        auditScoreChange(operatorUserId, player, "DOWN", normalizedAmount, agentScore, playerBalance,
                machineLedgerId == null ? 0L : machineLedgerId);
        return new ScoreChange("DOWN", normalizedAmount, agentScore, playerBalance, machineLedgerId, false);
    }

    @Transactional
    public PlayerLinkAuthenticationService.IssuedLink currentLink(long accountUserId, long playerUserId) {
        Agent agent = requireManagedPlayerAgent(accountUserId, playerUserId);
        return playerLinkAuthenticationService.currentForAgent(playerUserId, accountUserId);
    }

    @Transactional(readOnly = true)
    public OperationsSummary operations(long accountUserId, String day) {
        Agent agent = requireActiveAgent(accountUserId);
        LocalDate businessDay = day == null || day.isBlank()
                ? LocalDate.now(BUSINESS_ZONE)
                : parseBusinessDay(day);
        Instant from = businessDay.atTime(6, 0).atZone(BUSINESS_ZONE).toInstant();
        Instant to = businessDay.plusDays(1).atTime(6, 0).atZone(BUSINESS_ZONE).toInstant();
        List<OperationsSummary> rows = jdbc.query("""
                SELECT COALESCE(SUM(CASE WHEN b.settlement_status <> 'CANCELED' THEN 1 ELSE 0 END), 0) bet_count,
                       COALESCE(SUM(CASE WHEN b.settlement_status <> 'CANCELED' THEN b.stake ELSE 0 END), 0) turnover,
                       COALESCE(SUM(CASE WHEN b.settlement_status NOT IN ('PENDING', 'CANCELED')
                                         THEN b.net_profit ELSE 0 END), 0) net_profit,
                       COALESCE(SUM(CASE WHEN b.settlement_status = 'PENDING' THEN 1 ELSE 0 END), 0) pending_bet_count,
                       COALESCE(SUM(CASE WHEN b.settlement_status <> 'CANCELED'
                                          AND COALESCE(a.player_kind, b.player_kind_snapshot) = 'NORMAL'
                                          THEN b.stake ELSE 0 END), 0) normal_turnover,
                       COALESCE(SUM(CASE WHEN b.settlement_status <> 'CANCELED'
                                          AND COALESCE(a.player_kind, b.player_kind_snapshot) = 'BOT'
                                          THEN b.stake ELSE 0 END), 0) bot_turnover,
                       COUNT(DISTINCT CASE WHEN b.settlement_status <> 'CANCELED' THEN b.user_id END) active_player_count
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id = b.user_id
                 WHERE COALESCE(a.agent_id, b.agent_id_snapshot) = ? AND b.created_at >= ? AND b.created_at < ?
                """, (rs, rowNum) -> new OperationsSummary(
                businessDay.toString(), rs.getLong("bet_count"), rs.getBigDecimal("turnover"),
                rs.getBigDecimal("net_profit"), rs.getLong("pending_bet_count"),
                rs.getBigDecimal("normal_turnover"), rs.getBigDecimal("bot_turnover"),
                rs.getLong("active_player_count")), agent.id(), Timestamp.from(from), Timestamp.from(to));
        return rows.isEmpty() ? new OperationsSummary(businessDay.toString(), 0, BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), 0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), 0)
                : rows.get(0);
    }

    @Transactional(readOnly = true)
    public BetPage bets(long accountUserId, long playerUserId, int page, int pageSize) {
        Agent agent = requireManagedPlayerAgentReadOnly(accountUserId, playerUserId);
        validatePage(page, pageSize);
        AgentRepository.AgentPlayerRow player = requirePlayer(agent.id(), playerUserId);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_bet b
                 WHERE b.user_id = ?
                """, Long.class, player.accountId());
        List<BetRow> items = jdbc.query("""
                SELECT b.id, b.bet_code, b.issue_number, b.ball_number, b.play_type,
                       b.parameters_text, b.stake, b.odds_snapshot, b.settlement_status,
                       b.net_profit, b.explanation, b.created_at, b.settled_at
                  FROM game_bet b
                 WHERE b.user_id = ?
                 ORDER BY b.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new BetRow(rs.getLong("id"), rs.getString("bet_code"),
                rs.getString("issue_number"), rs.getInt("ball_number"),
                command(rs.getString("play_type"), rs.getString("parameters_text"), rs.getBigDecimal("stake")),
                rs.getBigDecimal("stake"), rs.getBigDecimal("odds_snapshot"), rs.getString("settlement_status"),
                rs.getBigDecimal("net_profit"), rs.getString("explanation"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("settled_at"))),
                player.accountId(), pageSize, (long) (page - 1) * pageSize);
        return new BetPage(items, page, pageSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public LedgerPage ledger(long accountUserId, long playerUserId, int page, int pageSize) {
        Agent agent = requireManagedPlayerAgentReadOnly(accountUserId, playerUserId);
        validatePage(page, pageSize);
        AgentRepository.AgentPlayerRow player = requirePlayer(agent.id(), playerUserId);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM demo_balance_ledger l
                 WHERE l.user_id = ?
                """, Long.class, player.accountId());
        List<LedgerRow> items = jdbc.query("""
                SELECT l.id, l.operation_type, l.amount, l.balance_before, l.balance_after,
                       l.reason, l.created_at
                  FROM demo_balance_ledger l
                 WHERE l.user_id = ?
                 ORDER BY l.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new LedgerRow(rs.getLong("id"), rs.getString("operation_type"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"), rs.getString("reason"),
                instant(rs.getTimestamp("created_at"))), player.accountId(), pageSize,
                (long) (page - 1) * pageSize);
        return new LedgerPage(items, page, pageSize, total == null ? 0 : total);
    }

    @Transactional
    public PlayerLinkAuthenticationService.IssuedLink rotateLink(long accountUserId, long playerUserId) {
        Agent agent = requireManagedPlayerAgent(accountUserId, playerUserId);
        return playerLinkAuthenticationService.rotateForAgent(playerUserId, accountUserId);
    }

    @Transactional
    public void revokeLink(long accountUserId, long playerUserId, long linkId) {
        Agent agent = requireManagedPlayerAgent(accountUserId, playerUserId);
        playerLinkAuthenticationService.revokeForAgent(playerUserId, linkId, accountUserId);
    }

    @Transactional
    public void restoreLink(long accountUserId, long playerUserId, long linkId) {
        Agent agent = requireManagedPlayerAgent(accountUserId, playerUserId);
        playerLinkAuthenticationService.restoreForAgent(playerUserId, linkId, accountUserId);
    }

    @Transactional(readOnly = true)
    public AgentPlayerPage listPlayers(long accountUserId, String kind, String keyword,
                                       int page, int pageSize) {
        AgentRepository.AgentRow agent = overview(accountUserId);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("AGENT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        String normalizedKind = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase();
        if (normalizedKind != null && !"NORMAL".equals(normalizedKind) && !"BOT".equals(normalizedKind)) {
            throw BusinessException.badRequest("AGENT_PLAYER_KIND_INVALID", "玩家分类无效");
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return new AgentPlayerPage(
                agentRepository.findPlayersByAgent(agent.id(), normalizedKind, normalizedKeyword, page, pageSize),
                page, pageSize,
                agentRepository.countPlayersByAgent(agent.id(), normalizedKind, normalizedKeyword));
    }

    private void requireConsole(long accountUserId) {
        if (accountUserId <= 0 || !permissionService.hasPermission(accountUserId, "AGENT_CONSOLE_READ")) {
            throw BusinessException.forbidden("AGENT_CONSOLE_FORBIDDEN", "没有代理后台访问权限");
        }
    }

    private void requirePlayerManage(long accountUserId) {
        if (accountUserId <= 0 || !permissionService.hasPermission(accountUserId, "AGENT_PLAYER_MANAGE")) {
            throw BusinessException.forbidden("AGENT_PLAYER_FORBIDDEN", "没有代理玩家管理权限");
        }
    }

    private Agent requireActiveAgent(long accountUserId) {
        Agent agent = agentRepository.findAgentByAccountUserId(accountUserId)
                .orElseThrow(() -> BusinessException.forbidden("AGENT_PROFILE_MISSING", "当前账号没有代理资料"));
        if (!agent.active()) {
            throw BusinessException.forbidden("AGENT_DISABLED", "代理已停用");
        }
        return agent;
    }

    private void ensureOwnedByAgent(long agentId, long userId) {
        Long actualAgentId = agentRepository.findCurrentAgentIdForUpdate(userId)
                .orElseThrow(() -> BusinessException.notFound("AGENT_PLAYER_NOT_FOUND", "玩家不存在"));
        if (actualAgentId != agentId) {
            throw BusinessException.forbidden("AGENT_PLAYER_OUT_OF_SCOPE", "玩家不属于当前代理");
        }
    }

    private AgentRepository.AgentPlayerRow requirePlayer(long agentId, long userId) {
        return agentRepository.findPlayerByAgentAndUser(agentId, userId)
                .orElseThrow(() -> BusinessException.notFound("AGENT_PLAYER_NOT_FOUND", "玩家不存在"));
    }

    private Agent requireManagedPlayerAgent(long accountUserId, long playerUserId) {
        requirePlayerManage(accountUserId);
        Agent agent = requireActiveAgent(accountUserId);
        ensureOwnedByAgent(agent.id(), playerUserId);
        requirePlayer(agent.id(), playerUserId);
        return agent;
    }

    private Agent requireManagedPlayerAgentReadOnly(long accountUserId, long playerUserId) {
        requirePlayerManage(accountUserId);
        Agent agent = requireActiveAgent(accountUserId);
        Long actualAgentId = agentRepository.findCurrentAgentId(playerUserId)
                .orElseThrow(() -> BusinessException.notFound("AGENT_PLAYER_NOT_FOUND", "玩家不存在"));
        if (actualAgentId != agent.id()) {
            throw BusinessException.forbidden("AGENT_PLAYER_OUT_OF_SCOPE", "玩家不属于当前代理");
        }
        requirePlayer(agent.id(), playerUserId);
        return agent;
    }

    private void auditScoreChange(long operator, AgentRepository.AgentPlayerRow player, String direction,
                                  BigDecimal amount, BigDecimal agentScore, BigDecimal playerBalance,
                                  long ledgerId) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, 'AGENT_PLAYER_MANAGE', 'POST', ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, "/api/agent/players/" + player.userId() + "/score",
                Long.toString(player.userId()), "direction=" + direction + ",amount=" + amount
                        + ",agentScore=" + agentScore + ",playerBalance=" + playerBalance
                        + ",ledgerId=" + ledgerId);
    }

    /**
     * 机器给玩家上下分的金额口径（计划 §3.5）。
     *
     * <p>修复前是 {@code setScale(2, HALF_UP)}：0.001 会被**静默**变成 0.00，机器积分与玩家钱包都不会动，
     * 使用者却看到"操作成功"。现在改为共享的两位小数严格校验：</p>
     * <ul>
     *   <li>超过两位小数 → 400 {@code MACHINE_SCORE_INVALID}「机器积分最多两位小数」；</li>
     *   <li>负数 → 400 {@code MACHINE_SCORE_INVALID}「机器积分不能为负数」；</li>
     *   <li>零 / {@code null} / 过大 → 保持修复前既有的 {@code AGENT_SCORE_AMOUNT_INVALID}，不扩大改动面。</li>
     * </ul>
     */
    private static BigDecimal positiveAmount(BigDecimal amount) {
        if (amount == null) {
            throw BusinessException.badRequest("AGENT_SCORE_AMOUNT_INVALID", "上下分金额必须大于零");
        }
        BigDecimal normalized = MoneyAmounts.requireTwoDecimals(amount, "MACHINE_SCORE_INVALID",
                "上下分金额不能过大", "机器积分最多两位小数");
        if (normalized.signum() < 0) {
            throw BusinessException.badRequest("MACHINE_SCORE_INVALID", "机器积分不能为负数");
        }
        if (normalized.signum() == 0) {
            throw BusinessException.badRequest("AGENT_SCORE_AMOUNT_INVALID", "上下分金额必须大于零");
        }
        if (normalized.compareTo(MAX_SCORE_AMOUNT) > 0) {
            throw BusinessException.badRequest("AGENT_SCORE_AMOUNT_INVALID", "上下分金额必须大于零且不能过大");
        }
        return normalized;
    }

    private static String requiredKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw BusinessException.badRequest("AGENT_SCORE_IDEMPOTENCY_REQUIRED", "幂等键不能为空且不能超过 128 个字符");
        }
        return idempotencyKey.trim();
    }

    private static LocalDate parseBusinessDay(String value) {
        try {
            LocalDate parsed = LocalDate.parse(value.trim());
            if (parsed.isAfter(LocalDate.now(BUSINESS_ZONE))) {
                throw BusinessException.badRequest("AGENT_OPERATIONS_DAY_INVALID", "不能查询未来业务日");
            }
            return parsed;
        } catch (java.time.format.DateTimeParseException exception) {
            throw BusinessException.badRequest("AGENT_OPERATIONS_DAY_INVALID", "业务日格式必须为 yyyy-MM-dd");
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("AGENT_DETAIL_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
    }

    private static String command(String playType, String parametersText, BigDecimal stake) {
        try {
            List<Integer> parameters = java.util.Arrays.stream(
                            parametersText == null ? new String[0] : parametersText.split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).map(Integer::valueOf).toList();
            return BetCommandFormatter.format(PlayType.valueOf(playType), parameters, stake);
        } catch (RuntimeException exception) {
            return playType + " " + (parametersText == null ? "" : parametersText);
        }
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record AgentPlayerPage(List<AgentRepository.AgentPlayerRow> items, int page, int pageSize, long total) {
    }

    /**
     * 上下分结果。
     *
     * @param ledgerId 流水编号：{@code changeScore}/{@code changeScoreForAgent} 为**玩家钱包流水**
     *                 （{@code demo_balance_ledger.id}）；{@link #settleApprovedDownRequest} 为
     *                 **机器账本流水**（{@code agent_ledger.id}，下分结算不动玩家钱包）；
     *                 托的下分结算不写机器账本，因此为 {@code null}。
     */
    public record ScoreChange(String direction, BigDecimal amount, BigDecimal agentScore,
                              BigDecimal playerBalance, Long ledgerId, boolean replay) {
    }

    public record OperationsSummary(String day, long betCount, BigDecimal turnover, BigDecimal netProfit,
                                    long pendingBetCount, BigDecimal normalTurnover, BigDecimal botTurnover,
                                    long activePlayerCount) {
    }

    public record BetPage(List<BetRow> items, int page, int pageSize, long total) {
    }

    public record BetRow(long id, String betCode, String issueNumber, int ballNumber, String command,
                         BigDecimal stake, BigDecimal odds, String settlementStatus, BigDecimal netProfit,
                         String explanation, Instant createdAt, Instant settledAt) {
    }

    public record LedgerPage(List<LedgerRow> items, int page, int pageSize, long total) {
    }

    public record LedgerRow(long id, String operationType, BigDecimal amount, BigDecimal balanceBefore,
                            BigDecimal balanceAfter, String reason, Instant createdAt) {
    }
}
