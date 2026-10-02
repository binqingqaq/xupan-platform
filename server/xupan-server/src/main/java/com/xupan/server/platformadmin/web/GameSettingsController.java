package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.GameSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/admin/game-settings")
public class GameSettingsController {

    private final GameSettingsService service;

    public GameSettingsController(GameSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<GameResponse> list(Authentication authentication) {
        return service.list(operator(authentication).getUserId()).stream().map(GameResponse::from).toList();
    }

    @GetMapping("/{id}")
    public GameResponse get(Authentication authentication, @PathVariable long id) {
        return GameResponse.from(service.get(id, operator(authentication).getUserId()));
    }

    @PostMapping
    public GameResponse create(Authentication authentication, @Valid @RequestBody GameRequest request) {
        return GameResponse.from(service.create(request.toInput(), operator(authentication).getUserId()));
    }

    @PutMapping("/{id}")
    public GameResponse update(Authentication authentication, @PathVariable long id,
                               @RequestParam long version, @Valid @RequestBody GameRequest request) {
        return GameResponse.from(service.update(id, request.toInput(), version,
                operator(authentication).getUserId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable long id) {
        service.delete(id, operator(authentication).getUserId());
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record GameRequest(@NotBlank String gameCode, @NotBlank String displayName,
                              @NotBlank String ballIndexes, String drawSourceUrl, int sortOrder,
                              @NotBlank String algorithm, String playPrefix, boolean switchEnabled,
                              boolean specialEnabled, @NotBlank String specialModel,
                              boolean keyboardEnabled, @NotBlank String status,
                              BigDecimal oddsAte, BigDecimal oddsAdx, BigDecimal oddsBte,
                              BigDecimal oddsBdx, BigDecimal oddsCte, BigDecimal oddsCdx,
                              BigDecimal oddsDte, BigDecimal oddsDdx) {
        GameSettingsService.GameInput toInput() {
            return new GameSettingsService.GameInput(gameCode, displayName, ballIndexes, drawSourceUrl,
                    sortOrder, algorithm, playPrefix, switchEnabled, specialEnabled, specialModel,
                    keyboardEnabled, status, oddsAte, oddsAdx, oddsBte, oddsBdx,
                    oddsCte, oddsCdx, oddsDte, oddsDdx);
        }
    }

    public record GameResponse(long id, String gameCode, String displayName, String ballIndexes,
                               String drawSourceUrl, int sortOrder, String algorithm, String playPrefix,
                               boolean switchEnabled, boolean specialEnabled, String specialModel,
                               boolean keyboardEnabled, String status, long version, Instant updatedAt,
                               BigDecimal oddsAte, BigDecimal oddsAdx, BigDecimal oddsBte,
                               BigDecimal oddsBdx, BigDecimal oddsCte, BigDecimal oddsCdx,
                               BigDecimal oddsDte, BigDecimal oddsDdx) {
        static GameResponse from(GameSettingsService.GameRow row) {
            return new GameResponse(row.id(), row.gameCode(), row.displayName(), row.ballIndexes(),
                    row.drawSourceUrl(), row.sortOrder(), row.algorithm(), row.playPrefix(),
                    row.switchEnabled(), row.specialEnabled(), row.specialModel(), row.keyboardEnabled(),
                    row.status(), row.version(), row.updatedAt(), row.oddsAte(), row.oddsAdx(),
                    row.oddsBte(), row.oddsBdx(), row.oddsCte(), row.oddsCdx(), row.oddsDte(), row.oddsDdx());
        }
    }
}
