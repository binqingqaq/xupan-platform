package com.xupan.server.game.web;

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

import java.util.Map;

@RestController
@RequestMapping("/api/demo/game")
public class DemoGameController {

    private final DemoGameService gameService;

    public DemoGameController(DemoGameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/current")
    public DemoGameService.GameView current() {
        return gameService.current();
    }

    @PostMapping("/bets")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoGameService.BetView placeBet(@Valid @RequestBody PlaceBetRequest request) {
        return gameService.placeBet(request);
    }

    @PostMapping("/admin/draw")
    public DemoGameService.GameView draw(@Valid @RequestBody DrawRequest request) {
        return gameService.draw(request.numbers());
    }

    @PostMapping("/admin/reset")
    public DemoGameService.GameView resetIssue() {
        return gameService.resetIssue();
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
}
