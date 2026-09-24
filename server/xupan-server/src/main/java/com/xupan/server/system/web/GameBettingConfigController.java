package com.xupan.server.system.web;

import com.xupan.server.game.service.GameBettingConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/admin/player-desk/betting-config")
public class GameBettingConfigController {

    private final GameBettingConfigService service;

    public GameBettingConfigController(GameBettingConfigService service) {
        this.service = service;
    }

    @GetMapping
    public GameBettingConfigService.BettingConfigView get() {
        return service.get();
    }

    @PutMapping("/display")
    public GameBettingConfigService.BettingConfigView updateDisplay(
            @RequestBody DisplayConfigRequest request) {
        return service.updateDisplay(request.displayOdds(), request.specialOdds(), request.specialRebate());
    }

    @PutMapping("/limits")
    public GameBettingConfigService.BettingConfigView updateLimits(
            @RequestBody GameBettingConfigService.LimitConfigRequest request) {
        return service.updateLimits(request);
    }

    public record DisplayConfigRequest(Integer displayOdds, BigDecimal specialOdds, Integer specialRebate) {
    }
}
