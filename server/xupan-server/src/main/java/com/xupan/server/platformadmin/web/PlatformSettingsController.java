package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api")
public class PlatformSettingsController {

    private final PlatformSettingsService service;

    public PlatformSettingsController(PlatformSettingsService service) {
        this.service = service;
    }

    @GetMapping("/admin/settings")
    public SettingsResponse get(Authentication authentication) {
        return SettingsResponse.from(service.get(operator(authentication).getUserId()));
    }

    @PutMapping("/admin/settings")
    public SettingsResponse update(Authentication authentication,
                                   @Valid @RequestBody SettingsRequest request) {
        return SettingsResponse.from(service.update(request.toInput(), request.version(),
                operator(authentication).getUserId()));
    }

    @PostMapping("/admin/settings/delete-all-accounts")
    public DeleteAllAccountsResponse deleteAllAccounts(Authentication authentication,
                                                       @Valid @RequestBody DeleteAllAccountsRequest request) {
        long operator = operator(authentication).getUserId();
        PlatformSettingsService.DeleteAllAccountsResult result = request.preview()
                ? service.previewDeleteAllAccounts(request.confirm(), operator)
                : service.deleteAllAccounts(request.confirm(), operator);
        return DeleteAllAccountsResponse.from(result);
    }
    @GetMapping("/platform/public-settings")
    public PublicSettingsResponse publicSettings(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return PublicSettingsResponse.from(service.publicSettings());
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record SettingsRequest(@NotBlank String siteTitle, String announcement, String domainLinks,
                                  String chatWarning, String information, boolean headerEnabled,
                                  boolean statusBarEnabled, boolean keyboardMode, long version) {
        PlatformSettingsService.SettingsInput toInput() {
            return new PlatformSettingsService.SettingsInput(siteTitle, announcement, domainLinks,
                    chatWarning, information, headerEnabled, statusBarEnabled, keyboardMode);
        }
    }

    public record SettingsResponse(long id, String siteTitle, String announcement, String domainLinks,
                                   String chatWarning, String information, boolean headerEnabled,
                                   boolean statusBarEnabled, boolean keyboardMode, long version,
                                   Long updatedBy, Instant updatedAt) {
        static SettingsResponse from(PlatformSettingsService.SettingsView settings) {
            return new SettingsResponse(settings.id(), settings.siteTitle(), settings.announcement(),
                    settings.domainLinks(), settings.chatWarning(), settings.information(),
                    settings.headerEnabled(), settings.statusBarEnabled(), settings.keyboardMode(),
                    settings.version(), settings.updatedBy(), settings.updatedAt());
        }
    }

    public record DeleteAllAccountsRequest(@NotBlank String confirm, boolean preview) {
    }

    public record DeleteAllAccountsResponse(boolean preview, Counts counts, String message) {
        static DeleteAllAccountsResponse from(PlatformSettingsService.DeleteAllAccountsResult result) {
            return new DeleteAllAccountsResponse(result.preview(), Counts.from(result.counts()), result.message());
        }

        public record Counts(long admins, long robots, long players, long flyers) {
            static Counts from(PlatformSettingsService.DeleteAllAccountsCounts counts) {
                return new Counts(counts.admins(), counts.robots(), counts.players(), counts.flyers());
            }
        }
    }
    public record PublicSettingsResponse(String siteTitle, String announcement, String chatWarning,
                                         boolean headerEnabled, boolean statusBarEnabled,
                                         boolean keyboardMode, long version) {
        static PublicSettingsResponse from(PlatformSettingsService.PublicSettingsView settings) {
            return new PublicSettingsResponse(settings.siteTitle(), settings.announcement(),
                    settings.chatWarning(), settings.headerEnabled(), settings.statusBarEnabled(),
                    settings.keyboardMode(), settings.version());
        }
    }
}
