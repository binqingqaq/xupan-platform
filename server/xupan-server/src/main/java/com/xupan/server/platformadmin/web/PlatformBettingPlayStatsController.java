package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformBettingPlayStatsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/betting-play-stats")
public class PlatformBettingPlayStatsController {

    private final PlatformBettingPlayStatsService service;

    public PlatformBettingPlayStatsController(PlatformBettingPlayStatsService service) {
        this.service = service;
    }

    @GetMapping("/current")
    public CurrentResponse current(Authentication authentication,
                                   @RequestParam(defaultValue = "AU8") String gameCode,
                                   @RequestParam(defaultValue = "false") boolean includeBots) {
        return CurrentResponse.from(service.current(gameCode, includeBots, operator(authentication).getUserId()));
    }

    @PutMapping("/current/balls/{ballNumber}")
    public PresetResponse preset(Authentication authentication, @PathVariable int ballNumber,
                                 @Valid @RequestBody PresetRequest request) {
        return PresetResponse.from(service.preset(request.gameCode(), request.issueNumber(),
                ballNumber, request.number(),
                operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record PresetRequest(@NotBlank String gameCode, @NotBlank String issueNumber, Integer number) {
    }

    public record CurrentResponse(String gameCode, String gameName, String issueNumber, String phase,
                                  int ballCount, Instant bettingEndsAt, Instant drawEndsAt, Instant serverNow,
                                  List<BallResponse> balls) {
        static CurrentResponse from(PlatformBettingPlayStatsService.CurrentStats stats) {
            return new CurrentResponse(stats.gameCode(), stats.gameName(), stats.issueNumber(), stats.phase(),
                    stats.ballCount(), stats.bettingEndsAt(), stats.drawEndsAt(), stats.serverNow(),
                    stats.balls().stream().map(BallResponse::from).toList());
        }
    }

    public record BallResponse(int ballNumber, Integer presetNumber, long orderCount, BigDecimal totalStake,
                               List<PlayResponse> plays) {
        static BallResponse from(PlatformBettingPlayStatsService.BallStats ball) {
            return new BallResponse(ball.ballNumber(), ball.presetNumber(), ball.orderCount(), ball.totalStake(),
                    ball.plays().stream().map(PlayResponse::from).toList());
        }
    }

    public record PlayResponse(String ruleKey, String playType, String playName, long orderCount, BigDecimal totalStake) {
        static PlayResponse from(PlatformBettingPlayStatsService.PlayStats play) {
            return new PlayResponse(play.ruleKey(), play.playType(), play.playName(), play.orderCount(), play.totalStake());
        }
    }

    public record PresetResponse(String gameCode, String issueNumber, String phase, int ballNumber,
                                 Integer presetNumber, Instant drawEndsAt, Instant serverNow, String message) {
        static PresetResponse from(PlatformBettingPlayStatsService.PresetSummary summary) {
            return new PresetResponse(summary.gameCode(), summary.issueNumber(), summary.phase(),
                    summary.ballNumber(), summary.presetNumber(), summary.drawEndsAt(), summary.serverNow(),
                    summary.presetNumber() == null ? "预设已清除，该球将在开奖时随机生成"
                            : "第" + summary.ballNumber() + "球已固定为" + summary.presetNumber());
        }
    }
}
