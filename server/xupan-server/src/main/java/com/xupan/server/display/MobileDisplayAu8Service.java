package com.xupan.server.display;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class MobileDisplayAu8Service {

    private static final String GAME_CODE = "AU8";

    private final GameDataRepository repository;

    public MobileDisplayAu8Service(GameDataRepository repository) {
        this.repository = repository;
    }

    public MobileDisplayAu8Response getDraw() {
        MobileDisplayAu8Response.CurrentIssue currentIssue = repository.findCurrentIssue(GAME_CODE)
                .filter(issue -> issue.ballCount() == 7)
                .map(issue -> new MobileDisplayAu8Response.CurrentIssue(
                        issue.issueNumber(), issue.phase(), issue.bettingEndsAt(), issue.drawEndsAt()))
                .orElse(null);

        DrawResult latestDraw = repository.findSettledIssues(GAME_CODE, 7, 1, 0).stream()
                .findFirst()
                .map(MobileDisplayAu8Service::toDrawResult)
                .orElse(null);

        return new MobileDisplayAu8Response(GAME_CODE, "快乐8", currentIssue, latestDraw);
    }

    public HistoryResponse getHistory(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) {
            throw BusinessException.badRequest("MOBILE_AU8_HISTORY_QUERY_INVALID", "开奖历史分页参数无效");
        }
        long offset = (long) (page - 1) * pageSize;
        List<DrawResult> items = repository.findSettledIssues(GAME_CODE, 7, pageSize, offset).stream()
                .map(MobileDisplayAu8Service::toDrawResult)
                .toList();
        return new HistoryResponse(items, page, pageSize, repository.countSettledIssues(GAME_CODE, 7));
    }

    private static DrawResult toDrawResult(GameDataRepository.IssueRecord issue) {
        List<Ball> balls = issue.numbers().stream()
                .map(number -> {
                    BallResult result = BallResult.fromNumber(number);
                    return new Ball(
                            number, result.fan(), result.parity(), result.size());
                })
                .toList();
        return new DrawResult(issue.issueNumber(), issue.settledAt(), balls);
    }

    public record HistoryResponse(List<DrawResult> items, int page, int pageSize, long total) {
    }

    public record DrawResult(String issueNumber, Instant drawnAt, List<Ball> balls) {
    }

    public record Ball(int number, int fan, String parity, String size) {
    }

    public record MobileDisplayAu8Response(
            String gameCode,
            String displayName,
            CurrentIssue currentIssue,
            MobileDisplayAu8Service.DrawResult latestDraw
    ) {
        public record CurrentIssue(String issueNumber, String phase, Instant bettingEndsAt, Instant drawEndsAt) {
        }

    }
}
