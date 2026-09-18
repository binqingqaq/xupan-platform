package com.xupan.server.game.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.service.DemoGameService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.Map;

@RestController
@RequestMapping("/api/demo/game")
public class DemoGameController {

    private final DemoGameService gameService;

    public DemoGameController(DemoGameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/current")
    public DemoGameService.GameView current(Authentication authentication) {
        return gameService.current(authenticatedUser(authentication).getUserId());
    }

    @PostMapping("/bets")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoGameService.BetView placeBet(Authentication authentication,
                                            @Valid @RequestBody PlaceBetRequest request) {
        return gameService.placeBet(authenticatedUser(authentication).getUserId(), request);
    }

    @GetMapping("/bets/summary")
    public DemoGameService.BetSummaryView betSummary(Authentication authentication) {
        return gameService.betSummary(authenticatedUser(authentication).getUserId());
    }

    @PostMapping("/admin/draw")
    public DemoGameService.GameView draw(Authentication authentication,
                                         @Valid @RequestBody DrawRequest request) {
        return gameService.draw(authenticatedUser(authentication).getUserId(), request.numbers());
    }

    @PostMapping("/admin/reset")
    public DemoGameService.GameView resetIssue(Authentication authentication) {
        return gameService.resetIssue(authenticatedUser(authentication).getUserId());
    }

    @PutMapping("/admin/odds/{playType}")
    public DemoGameService.OddsView updateOdds(@PathVariable PlayType playType,
                                               @Valid @RequestBody OddsRequest request) {
        return gameService.updateOdds(playType, request.odds());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBusinessError(RuntimeException exception) {
        return Map.of("message", exception.getMessage());
    }

    private static AuthenticatedUser authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("GAME_BET_USER_REQUIRED");
        }
        return user;
    }
}
