package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.ReportNetworkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/report-networks")
public class ReportNetworkController {

    private final ReportNetworkService service;

    public ReportNetworkController(ReportNetworkService service) {
        this.service = service;
    }

    @GetMapping
    public List<NetworkResponse> list(Authentication authentication) {
        return service.list(operator(authentication).getUserId()).stream().map(NetworkResponse::from).toList();
    }

    @PostMapping
    public NetworkResponse create(Authentication authentication, @Valid @RequestBody NetworkRequest request) {
        return NetworkResponse.from(service.create(request.toInput(), operator(authentication).getUserId()));
    }

    @PutMapping("/{id}")
    public NetworkResponse update(Authentication authentication, @PathVariable long id,
                                  @RequestParam long version,
                                  @Valid @RequestBody NetworkRequest request) {
        return NetworkResponse.from(service.update(id, request.toInput(), version,
                operator(authentication).getUserId()));
    }

    @PatchMapping("/{id}/status")
    public NetworkResponse changeStatus(Authentication authentication, @PathVariable long id,
                                        @RequestParam long version,
                                        @Valid @RequestBody StatusRequest request) {
        return NetworkResponse.from(service.changeStatus(id, request.status(), version,
                operator(authentication).getUserId()));
    }

    @DeleteMapping("/{id}")
    public void delete(Authentication authentication, @PathVariable long id, @RequestParam long version) {
        service.delete(id, version, operator(authentication).getUserId());
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record NetworkRequest(@NotBlank String code, @NotBlank String name,
                                 @NotBlank String websiteUrl, String status) {
        ReportNetworkService.NetworkInput toInput() {
            return new ReportNetworkService.NetworkInput(code, name, websiteUrl, status);
        }
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record NetworkResponse(long id, String code, String name, String websiteUrl, String status,
                                  long version, Instant createdAt, Instant updatedAt) {
        static NetworkResponse from(ReportNetworkService.NetworkRow row) {
            return new NetworkResponse(row.id(), row.code(), row.name(), row.websiteUrl(), row.status(),
                    row.version(), row.createdAt(), row.updatedAt());
        }
    }
}
