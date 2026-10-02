package com.xupan.server.system.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.system.service.BetBoardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/player-desk/bet-board")
public class BetBoardController {

    private final BetBoardService service;

    public BetBoardController(BetBoardService service) {
        this.service = service;
    }

    @GetMapping
    public BoardResponse board(Authentication authentication,
                               @RequestParam(defaultValue = "NORMAL") String kind,
                               @RequestParam(defaultValue = "500") int limit) {
        return BoardResponse.from(service.board(kind, limit, userId(authentication)));
    }

    private static long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user.getUserId();
    }

    public record BoardResponse(String issueNumber, String phase, Instant serverNow, Instant phaseEndsAt,
                                long normalCount, long botCount, BigDecimal normalStake, BigDecimal botStake,
                                List<BetItemResponse> items, List<DrawHistoryResponse> history) {
        static BoardResponse from(BetBoardService.Board board) {
            return new BoardResponse(board.issueNumber(), board.phase(), board.serverNow(),
                    board.phaseEndsAt(), board.normalCount(), board.botCount(),
                    board.normalStake(), board.botStake(),
                    board.items().stream().map(BetItemResponse::from).toList(),
                    board.history().stream().map(DrawHistoryResponse::from).toList());
        }
    }

    /**
     * 下注榜的一行。
     *
     * <p>{@code reportStatus} 是 BY220 下注榜里每条注单上的 {@code flyers_status}：代理点该状态标记即可重新飞单
     * （{@code application/rbt/view/index/admin_list.html:1975-1992}）。重报入口属于下注榜，不另设"上报订单"区块。</p>
     */
    public record BetItemResponse(long id, String displayName, String playerKind, String betText,
                                  BigDecimal stake, String settlementStatus, String reportStatus,
                                  Instant createdAt) {
        static BetItemResponse from(BetBoardService.BetItem item) {
            return new BetItemResponse(item.id(), item.displayName(), item.playerKind(),
                    item.betText(), item.stake(), item.settlementStatus(), item.reportStatus(),
                    item.createdAt());
        }
    }

    public record DrawHistoryResponse(String issueNumber, String status, Instant settledAt,
                                      List<DrawBallResponse> balls) {
        static DrawHistoryResponse from(BetBoardService.DrawHistory history) {
            return new DrawHistoryResponse(history.issueNumber(), history.status(), history.settledAt(),
                    history.balls().stream().map(DrawBallResponse::from).toList());
        }
    }

    public record DrawBallResponse(int position, int number, int fan) {
        static DrawBallResponse from(BetBoardService.DrawBall ball) {
            return new DrawBallResponse(ball.position(), ball.number(), ball.fan());
        }
    }
}
