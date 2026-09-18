package com.xupan.server.system.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.game.service.DemoGameService;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.media.AvatarStorageService;
import com.xupan.server.system.service.TestPlayerAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/test-players")
public class TestPlayerAdminController {

    private final TestPlayerAdminService service;
    private final AvatarStorageService avatarStorageService;

    public TestPlayerAdminController(TestPlayerAdminService service, AvatarStorageService avatarStorageService) {
        this.service = service;
        this.avatarStorageService = avatarStorageService;
    }

    @GetMapping
    public TestPlayerPageResponse list(Authentication authentication,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        AuthenticatedUser operator = principal(authentication);
        return TestPlayerPageResponse.from(service.list(status, keyword, page, pageSize,
                operator.getUserId()));
    }

    @PostMapping
    public TestPlayerAdminResponse create(Authentication authentication,
                                          @Valid @RequestBody CreateTestPlayerRequest request) {
        AuthenticatedUser operator = principal(authentication);
        return toResponse(service.create(request.userCode(), request.displayName(), request.avatarKey(),
                operator.getUserId()));
    }

    @GetMapping("/{userCode}")
    public TestPlayerAdminResponse detail(Authentication authentication, @PathVariable String userCode) {
        return toResponse(service.detail(userCode, principal(authentication).getUserId()));
    }

    @PatchMapping("/{userCode}/status")
    public TestPlayerAdminResponse status(Authentication authentication, @PathVariable String userCode,
                                          @Valid @RequestBody ChangeUserStatusRequest request) {
        return toResponse(service.changeStatus(userCode, request.status(),
                principal(authentication).getUserId()));
    }

    @PostMapping("/{userCode}/balance/grants")
    public TestPlayerAdminResponse grant(Authentication authentication, @PathVariable String userCode,
                                         @Valid @RequestBody com.xupan.server.game.web.WalletGrantRequest request) {
        return toResponse(service.grant(userCode, request.amount(), request.reason(), request.idempotencyKey(),
                principal(authentication).getUserId()));
    }

    @PostMapping("/{userCode}/balance/reset")
    public TestPlayerAdminResponse reset(Authentication authentication, @PathVariable String userCode,
                                         @Valid @RequestBody ResetTestPlayerBalanceRequest request) {
        return toResponse(service.reset(userCode, request.reason(), request.idempotencyKey(),
                principal(authentication).getUserId()));
    }

    @PostMapping("/{userCode}/bets")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoGameService.BetView placeBet(Authentication authentication, @PathVariable String userCode,
                                            @Valid @RequestBody PlaceBetRequest request) {
        return service.placeBet(userCode, request, principal(authentication).getUserId());
    }

    @org.springframework.web.bind.annotation.PutMapping("/{userCode}/avatar")
    public AvatarResponse avatar(Authentication authentication, @PathVariable String userCode,
                                 @RequestPart("file") MultipartFile file) {
        String key = avatarStorageService.store(file).avatarKey();
        service.updateAvatar(userCode, key, principal(authentication).getUserId());
        return new AvatarResponse(key, "/api/media/avatars/" + key);
    }

    private static TestPlayerAdminResponse toResponse(TestPlayerAdminService.TestPlayerAdminView view) {
        return TestPlayerAdminResponse.from(view.player(), view.ledger());
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record AvatarResponse(String avatarKey, String url) {
    }
}
