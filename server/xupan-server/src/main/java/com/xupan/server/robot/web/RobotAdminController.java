package com.xupan.server.robot.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.robot.service.RobotAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/robots")
public class RobotAdminController {

    private final RobotAdminService robotAdminService;

    public RobotAdminController(RobotAdminService robotAdminService) {
        this.robotAdminService = robotAdminService;
    }

    @GetMapping
    public RobotAdminResponse.RobotPage list(Authentication authentication,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.RobotPage.from(
                robotAdminService.listRobots(operator.getUserId(), page, pageSize));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RobotAdminResponse.RobotDetail create(Authentication authentication,
                                                 @Valid @RequestBody RobotAdminRequest.CreateRobot request) {
        AuthenticatedUser operator = principal(authentication);
        RobotAdminService.RobotDetail detail = robotAdminService.createRobot(operator.getUserId(),
                request.robotCode(), request.displayName(), request.avatarKey(), request.weight(),
                request.delaySeconds());
        return RobotAdminResponse.RobotDetail.from(detail);
    }

    @GetMapping("/{robotId}")
    public RobotAdminResponse.RobotDetail detail(Authentication authentication,
                                                 @PathVariable long robotId) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.RobotDetail.from(
                robotAdminService.getRobot(operator.getUserId(), robotId));
    }

    @PutMapping("/{robotId}")
    public RobotAdminResponse.RobotDetail update(Authentication authentication,
                                                 @PathVariable long robotId,
                                                 @Valid @RequestBody RobotAdminRequest.UpdateRobot request) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.RobotDetail.from(robotAdminService.updateRobot(operator.getUserId(),
                robotId, request.displayName(), request.avatarKey(), request.weight(),
                request.delaySeconds()));
    }

    @PatchMapping("/{robotId}/status")
    public RobotAdminResponse.RobotDetail changeStatus(Authentication authentication,
                                                        @PathVariable long robotId,
                                                        @Valid @RequestBody RobotAdminRequest.ChangeStatus request) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.RobotDetail.from(
                robotAdminService.changeStatus(operator.getUserId(), robotId, request.status()));
    }

    @GetMapping("/{robotId}/templates")
    public RobotAdminResponse.TemplateList templates(Authentication authentication,
                                                     @PathVariable long robotId) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.TemplateList.from(
                robotAdminService.listTemplates(operator.getUserId(), robotId));
    }

    @PutMapping("/{robotId}/templates/{eventType}")
    public RobotAdminResponse.TemplateSummary updateTemplate(Authentication authentication,
                                                              @PathVariable long robotId,
                                                              @PathVariable String eventType,
                                                              @Valid @RequestBody RobotAdminRequest.UpdateTemplate request) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.TemplateSummary.from(robotAdminService.updateTemplate(
                operator.getUserId(), robotId, eventType, request.templateText()));
    }

    @PostMapping("/{robotId}/templates/{eventType}/preview")
    public RobotAdminResponse.TemplatePreview previewTemplate(Authentication authentication,
                                                               @PathVariable long robotId,
                                                               @PathVariable String eventType,
                                                               @Valid @RequestBody RobotAdminRequest.UpdateTemplate request) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.TemplatePreview.from(robotAdminService.previewTemplate(
                operator.getUserId(), robotId, eventType, request.templateText()));
    }

    @GetMapping("/dispatches")
    public RobotAdminResponse.DispatchPage dispatches(Authentication authentication,
                                                       @RequestParam(required = false) String issueNumber,
                                                       @RequestParam(required = false) String eventType,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Instant from,
                                                       @RequestParam(required = false) Instant to,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.DispatchPage.from(robotAdminService.listDispatches(
                operator.getUserId(), issueNumber, eventType, status, from, to, page, pageSize));
    }

    @PostMapping("/dispatches/{dispatchId}/retry")
    public RobotAdminResponse.DispatchSummary retry(Authentication authentication,
                                                    @PathVariable long dispatchId) {
        AuthenticatedUser operator = principal(authentication);
        return RobotAdminResponse.DispatchSummary.from(
                robotAdminService.retryDispatch(operator.getUserId(), dispatchId));
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }
}
