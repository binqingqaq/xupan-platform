package com.xupan.server.platformadmin.web;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.auth.domain.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class PlatformAdminResourceController {

    private final PlatformAdminService service;

    public PlatformAdminResourceController(PlatformAdminService service) {
        this.service = service;
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
    public MachineResponse updateMachine(Authentication authentication, @PathVariable long id, @Valid @RequestBody MachineRequest request) {
        return MachineResponse.from(service.updateMachine(id, request.toInput(), operator(authentication).getUserId()));
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

    public record SubAccountRequest(@NotBlank String username, String rawPassword, @NotBlank String displayName,
                                    BigDecimal score, Instant expiresAt, boolean subAccountManage,
                                    boolean machineManage, boolean unifiedReportEnabled, String reportUsername,
                                    String reportNetworkCode, String reportRouteCode) {
        PlatformAdminService.SubAccountInput toInput() {
            return new PlatformAdminService.SubAccountInput(username, rawPassword, displayName, score, expiresAt,
                    subAccountManage, machineManage, unifiedReportEnabled, reportUsername, reportNetworkCode, reportRouteCode);
        }
    }

    public record MachineRequest(@NotBlank String username, String rawPassword, String displayName, Long groupId,
                                 BigDecimal score, Instant expiresAt, boolean boardOpen, boolean robotManage,
                                 boolean chaseEnabled, int botCount, int closeSeconds, int cancelSeconds,
                                 BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                                 BigDecimal specialOddsRate, BigDecimal totalLimit, BigDecimal positiveLimit,
                                 BigDecimal angleLimit, BigDecimal strictLimit, BigDecimal tongLimit,
                                 BigDecimal carLimit, BigDecimal specialLimit, BigDecimal oddEvenLimit,
                                 BigDecimal bigSmallLimit, BigDecimal fanLimit, BigDecimal addLimit,
                                 BigDecimal playerMaxStake, BigDecimal playerMinStake, List<String> games) {
        PlatformAdminService.MachineInput toInput() {
            return new PlatformAdminService.MachineInput(username, rawPassword, displayName, groupId, score,
                    expiresAt, boardOpen, robotManage, chaseEnabled, botCount, closeSeconds, cancelSeconds,
                    rebateRate, oddsRate, specialRebateRate, specialOddsRate, totalLimit, positiveLimit,
                    angleLimit, strictLimit, tongLimit, carLimit, specialLimit, oddEvenLimit, bigSmallLimit,
                    fanLimit, addLimit, playerMaxStake, playerMinStake, games);
        }
    }

    public record StatusRequest(@NotBlank String status) {}

    public record SubAccountResponse(long id, String code, String username, String displayName, BigDecimal score,
                                     Instant expiresAt, String status, boolean subAccountManage,
                                     boolean machineManage, boolean unifiedReportEnabled,
                                     String reportNetworkCode, String reportRouteCode, long machineCount,
                                     Instant createdAt) {
        static SubAccountResponse from(PlatformAdminService.SubAccountRow row) {
            return new SubAccountResponse(row.id(), row.groupCode(), row.username(), row.displayName(), row.score(),
                    row.expiresAt(), row.status(), row.subAccountManage(), row.machineManage(),
                    row.unifiedReportEnabled(), row.reportNetworkCode(), row.reportRouteCode(),
                    row.machineCount(), row.createdAt());
        }
    }

    public record MachineResponse(long id, String code, String displayName, String accountUsername, long accountUserId,
                                  Long groupId, String groupUsername, BigDecimal score, Instant expiresAt,
                                  String status, boolean boardOpen, boolean robotManage, boolean chaseEnabled,
                                  int requestedBotCount, int closeSeconds, int cancelSeconds,
                                  BigDecimal rebateRate, BigDecimal oddsRate, BigDecimal specialRebateRate,
                                  BigDecimal specialOddsRate, BigDecimal totalLimit, BigDecimal positiveLimit,
                                  BigDecimal angleLimit, BigDecimal strictLimit, BigDecimal tongLimit,
                                  BigDecimal carLimit, BigDecimal specialLimit, BigDecimal oddEvenLimit,
                                  BigDecimal bigSmallLimit, BigDecimal fanLimit, BigDecimal addLimit,
                                  BigDecimal playerMaxStake, BigDecimal playerMinStake, long normalCount,
                                  long botCount) {
        static MachineResponse from(PlatformAdminService.MachineRow row) {
            return new MachineResponse(row.id(), row.code(), row.displayName(), row.accountUsername(),
                    row.accountUserId(), row.groupId(), row.groupUsername(), row.score(), row.expiresAt(),
                    row.status(), row.boardOpen(), row.robotManage(), row.chaseEnabled(), row.requestedBotCount(),
                    row.closeSeconds(), row.cancelSeconds(), row.rebateRate(), row.oddsRate(),
                    row.specialRebateRate(), row.specialOddsRate(), row.totalLimit(), row.positiveLimit(),
                    row.angleLimit(), row.strictLimit(), row.tongLimit(), row.carLimit(), row.specialLimit(),
                    row.oddEvenLimit(), row.bigSmallLimit(), row.fanLimit(), row.addLimit(), row.playerMaxStake(),
                    row.playerMinStake(), row.normalCount(), row.botCount());
        }
    }

    public record MachinePlayerResponse(long userId, long accountId, String internalCode, String displayName,
                                        String memberCode, String playerKind, String userStatus,
                                        String accountStatus, BigDecimal balance, Instant createdAt,
                                        Instant lastLoginAt) {
        static MachinePlayerResponse from(AgentRepository.AgentPlayerRow row) {
            return new MachinePlayerResponse(row.userId(), row.accountId(), row.internalCode(), row.displayName(),
                    row.memberCode(), row.playerKind(), row.userStatus(), row.accountStatus(), row.balance(),
                    row.createdAt(), row.lastLoginAt());
        }
    }
}
