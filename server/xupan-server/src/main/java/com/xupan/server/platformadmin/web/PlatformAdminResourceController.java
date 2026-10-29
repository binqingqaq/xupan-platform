package com.xupan.server.platformadmin.web;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuditQueryService;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class PlatformAdminResourceController {

    private final PlatformAdminService service;
    private final AuditQueryService auditQueryService;

    public PlatformAdminResourceController(PlatformAdminService service, AuditQueryService auditQueryService) {
        this.service = service;
        this.auditQueryService = auditQueryService;
    }

    /**
     * 查询操作审计（账本原则 3：{@code AUDIT_READ} 落地）。
     *
     * <p>BY220 {@code admin.php} 的"日志"菜单（{@code changed/loglist}）在模板里是注释掉的，
     * 因此这里只提供数据接口，**不新增菜单页面**，避免发明 BY220 未启用的业务流程。</p>
     */
    @GetMapping("/audit-logs")
    public AuditLogPage auditLogs(Authentication authentication,
                                  @RequestParam(required = false) Long operatorUserId,
                                  @RequestParam(required = false) String permissionCode,
                                  @RequestParam(required = false) String result,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int pageSize) {
        return AuditLogPage.from(auditQueryService.search(operator(authentication).getUserId(),
                operatorUserId, permissionCode, result, keyword, page, pageSize));
    }

    @GetMapping("/sub-accounts")
    public List<SubAccountResponse> subAccounts(Authentication authentication) {
        return service.listSubAccounts(operator(authentication).getUserId()).stream().map(SubAccountResponse::from).toList();
    }

    @PostMapping("/sub-accounts")
    public SubAccountResponse createSubAccount(Authentication authentication, @Valid @RequestBody SubAccountRequest request) {
        return SubAccountResponse.from(service.createSubAccount(request.toInput(), operator(authentication).getUserId()));
    }

    @PutMapping("/sub-accounts/{id}")
    public SubAccountResponse updateSubAccount(Authentication authentication, @PathVariable long id, @Valid @RequestBody SubAccountRequest request) {
        return SubAccountResponse.from(service.updateSubAccount(id, request.toInput(), operator(authentication).getUserId()));
    }

    @DeleteMapping("/sub-accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubAccount(Authentication authentication, @PathVariable long id) {
        service.deleteSubAccount(id, operator(authentication).getUserId());
    }

    @PatchMapping("/sub-accounts/{id}/status")
    public void changeSubAccountStatus(Authentication authentication, @PathVariable long id, @Valid @RequestBody StatusRequest request) {
        service.changeSubAccountStatus(id, request.status(), operator(authentication).getUserId());
    }

    @GetMapping("/machines")
    public List<MachineResponse> machines(Authentication authentication) {
        return service.listMachines(operator(authentication).getUserId()).stream().map(MachineResponse::from).toList();
    }

    @PostMapping("/machines")
    public MachineResponse createMachine(Authentication authentication, @Valid @RequestBody MachineRequest request) {
        return MachineResponse.from(service.createMachine(request.toInput(), operator(authentication).getUserId()));
    }

    @PutMapping("/machines/{id}")
    public MachineResponse updateMachine(Authentication authentication, @PathVariable long id,
                                         @RequestParam long expectedConfigVersion,
                                         @Valid @RequestBody MachineRequest request) {
        return MachineResponse.from(service.updateMachine(id, request.toInput(), expectedConfigVersion,
                operator(authentication).getUserId()));
    }

    @DeleteMapping("/machines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMachine(Authentication authentication, @PathVariable long id) {
        service.deleteMachine(id, operator(authentication).getUserId());
    }

    @PatchMapping("/machines/{id}/status")
    public void changeMachineStatus(Authentication authentication, @PathVariable long id, @Valid @RequestBody StatusRequest request) {
        service.changeMachineStatus(id, request.status(), operator(authentication).getUserId());
    }

    @GetMapping("/machines/{id}/players")
    public List<MachinePlayerResponse> machinePlayers(Authentication authentication, @PathVariable long id) {
        return service.listMachinePlayers(id, operator(authentication).getUserId()).stream().map(MachinePlayerResponse::from).toList();
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    /**
     * 子账号写入请求（BY220 {@code sub/form.html}）。
     *
     * <p>{@code username} 支持逗号分隔批量建号（BY220 {@code Users.php} 的 {@code explode(',')}）；
     * {@code score} 可空：新建时省略按 0，编辑时省略表示不改动积分；
     * {@code up}/{@code down} 是 BY220 编辑表单的「上分/下分」（相对增减，不能同时提交）；
     * {@code online} 是 BY220 的在线/下线开关（可空，省略表示不切换）。</p>
     */
    public record SubAccountRequest(@NotBlank String username, String rawPassword,
                                    BigDecimal score, Instant expiresAt, boolean subAccountManage,
                                    boolean machineManage, BigDecimal up, BigDecimal down, Boolean online) {
        PlatformAdminService.SubAccountInput toInput() {
            return new PlatformAdminService.SubAccountInput(username, rawPassword, score, expiresAt,
                    subAccountManage, machineManage, up, down, online);
        }
    }

    public record MachineRequest(@NotBlank String username, String rawPassword, Long groupId,
                                 BigDecimal score, Instant expiresAt, Boolean robotManage,
                                 boolean chaseEnabled, int botCount, int closeSeconds, int cancelSeconds,
                                 BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                                 BigDecimal specialOddsRate, BigDecimal totalLimit, BigDecimal positiveLimit,
                                 BigDecimal angleLimit, BigDecimal strictLimit, BigDecimal tongLimit,
                                 BigDecimal carLimit, BigDecimal specialLimit, BigDecimal oddEvenLimit,
                                 BigDecimal bigSmallLimit, BigDecimal fanLimit, BigDecimal addLimit,
                                 BigDecimal playerMaxStake, BigDecimal playerMinStake, List<String> games,
                                 String rebateMode) {
        PlatformAdminService.MachineInput toInput() {
            return new PlatformAdminService.MachineInput(username, rawPassword, groupId, score,
                    expiresAt, robotManage == null || robotManage, chaseEnabled, botCount, closeSeconds, cancelSeconds,
                    rebateRate, oddsRate, specialRebateRate, specialOddsRate, totalLimit, positiveLimit,
                    angleLimit, strictLimit, tongLimit, carLimit, specialLimit, oddEvenLimit, bigSmallLimit,
                    fanLimit, addLimit, playerMaxStake, playerMinStake, games, rebateMode);
        }
    }

    public record StatusRequest(@NotBlank String status) {}

    /**
     * 子账号响应。
     *
     * <p>{@code status} 是 BY220 口径的正常/停用；{@code accountStatus} 是登录账号的真实状态，
     * 值为 {@code LOCKED} 时前端显示「已锁定」并提供「解锁」（解锁走 {@code PATCH .../status ACTIVE}）。
     * {@code online}/{@code logtime} 是 BY220 {@code x_admin.online}/{@code x_admin.logtime}
     * （在线状态与最近登录时间，不参与登录校验）。</p>
     */
    public record SubAccountResponse(long id, String code, String username, BigDecimal score,
                                     Instant expiresAt, String status, String accountStatus, boolean subAccountManage,
                                     boolean machineManage, long machineCount, Instant createdAt,
                                     boolean online, Instant logtime) {
        static SubAccountResponse from(PlatformAdminService.SubAccountRow row) {
            return new SubAccountResponse(row.id(), row.groupCode(), row.username(), row.score(),
                    row.expiresAt(), row.status(), row.accountStatus(), row.subAccountManage(), row.machineManage(),
                    row.machineCount(), row.createdAt(), row.online(), row.logtime());
        }
    }

    /**
     * 机器账号响应。
     *
     * <p>{@code accountStatus} 独立于 {@code status}：账号可能被锁（{@code LOCKED}）而机器仍是"正常"，
     * 此时登录会一直提示用户名或密码错误，必须让超管看到并能解锁。</p>
     */
    public record MachineResponse(long id, String code, String accountUsername, long accountUserId,
                                  Long groupId, String groupUsername, BigDecimal score, Instant expiresAt,
                                  String status, String accountStatus, boolean manualBettingOpen, int configVersion,
                                  boolean robotManage,
                                  boolean chaseEnabled,
                                  int requestedBotCount, int closeSeconds, int cancelSeconds,
                                  BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                                  BigDecimal specialOddsRate, BigDecimal totalLimit, BigDecimal positiveLimit,
                                  BigDecimal angleLimit, BigDecimal strictLimit, BigDecimal tongLimit,
                                  BigDecimal carLimit, BigDecimal specialLimit, BigDecimal oddEvenLimit,
                                  BigDecimal bigSmallLimit, BigDecimal fanLimit, BigDecimal addLimit,
                                  BigDecimal playerMaxStake, BigDecimal playerMinStake, long normalCount,
                                  long botCount, String rebateMode, List<String> games) {
        static MachineResponse from(PlatformAdminService.MachineRow row) {
            return new MachineResponse(row.id(), row.code(), row.accountUsername(),
                    row.accountUserId(), row.groupId(), row.groupUsername(), row.score(), row.expiresAt(),
                    row.status(), row.accountStatus(), row.manualBettingOpen(), row.configVersion(),
                    row.robotManage(), row.chaseEnabled(),
                    row.requestedBotCount(),
                    row.closeSeconds(), row.cancelSeconds(), row.rebateRate(), row.oddsRate(),
                    row.specialRebateRate(), row.specialOddsRate(), row.totalLimit(), row.positiveLimit(),
                    row.angleLimit(), row.strictLimit(), row.tongLimit(), row.carLimit(), row.specialLimit(),
                    row.oddEvenLimit(), row.bigSmallLimit(), row.fanLimit(), row.addLimit(), row.playerMaxStake(),
                    row.playerMinStake(), row.normalCount(), row.botCount(), row.rebateMode(), row.games());
        }
    }

    /** 一页操作审计（账本原则 3）。 */
    public record AuditLogPage(List<AuditLogEntry> items, int page, int pageSize, long total) {
        static AuditLogPage from(AuditQueryService.Page x) {
            return new AuditLogPage(x.items().stream().map(AuditLogEntry::from).toList(),
                    x.page(), x.pageSize(), x.total());
        }
    }

    /**
     * 一行操作审计。
     *
     * <p>刻意不返回 {@code ip_digest}：它是摘要值，对本机运维没有帮助，
     * 减少一处可能被误当成"可还原来源"的字段。</p>
     */
    public record AuditLogEntry(long id, Long operatorUserId, String permissionCode, String httpMethod,
                                String requestPath, String resourceId, String result, String errorCode,
                                String requestSummary, Instant createdAt) {
        static AuditLogEntry from(com.xupan.server.auth.repository.OperationAuditRepository.AuditRow x) {
            return new AuditLogEntry(x.id(), x.operatorUserId(), x.permissionCode(), x.httpMethod(),
                    x.requestPath(), x.resourceId(), x.result(), x.errorCode(), x.requestSummary(),
                    x.createdAt());
        }
    }

    public record MachinePlayerResponse(long userId, long accountId, String internalCode, String displayName,
                                        String memberCode, String playerKind, String userStatus,
                                        String accountStatus, BigDecimal balance, Instant createdAt,
                                        Instant lastLoginAt) {        static MachinePlayerResponse from(AgentRepository.AgentPlayerRow row) {
            return new MachinePlayerResponse(row.userId(), row.accountId(), row.internalCode(), row.displayName(),
                    row.memberCode(), row.playerKind(), row.userStatus(), row.accountStatus(), row.balance(),
                    row.createdAt(), row.lastLoginAt());
        }
    }
}
