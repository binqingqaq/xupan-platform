package com.xupan.server.robot.service;

import com.xupan.server.chat.domain.ChatMessageVisibility;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.chat.domain.ChatMessageVisibility;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.domain.ChatMessageStatus;
import com.xupan.server.chat.domain.ChatSenderType;
import com.xupan.server.chat.repository.ChatMessageRepository;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.BetCommandFormatter;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.robot.domain.ChatRobot;
import com.xupan.server.robot.domain.RobotNoticeIntent;
import com.xupan.server.robot.repository.RobotNoticeIntentRepository;
import com.xupan.server.robot.repository.RobotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.List;

@Service
public class RobotNoticeDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RobotNoticeDispatchService.class);
    private static final int MAX_ATTEMPTS = 8;

    private final RobotNoticeIntentRepository intents;
    private final RobotRepository robots;
    private final ChatMessageService messages;
    private final TransactionTemplate transactions;
    private final AgentRepository agents;
    private final GameDataRepository games;
    private final RobotNoticeIntentService noticeIntents;
    private final ChatMessageRepository chatMessages;

    public RobotNoticeDispatchService(RobotNoticeIntentRepository intents, RobotRepository robots,
                                     ChatMessageService messages, TransactionTemplate transactions,
                                     AgentRepository agents, GameDataRepository games,
                                     RobotNoticeIntentService noticeIntents,
                                     ChatMessageRepository chatMessages) {
        this.intents = intents;
        this.robots = robots;
        this.messages = messages;
        this.transactions = transactions;
        this.agents = agents;
        this.games = games;
        this.noticeIntents = noticeIntents;
        this.chatMessages = chatMessages;
    }

    public int scanMachineCloseNotices(Instant now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("机器封盘提醒扫描参数无效");
        }
        var issue = games.findCurrentIssue("AU8").orElse(null);
        if (issue == null || !"BETTING".equals(issue.phase()) || issue.bettingEndsAt() == null) return 0;
        int enqueued = 0;
        for (AgentRepository.MachineCloseSchedule schedule : agents.findAu8MachineCloseSchedules()) {
            if (enqueued >= batchSize || !schedule.manualBettingOpen()) continue;
            Instant machineCloseAt = schedule.closeSeconds() <= 0 || issue.drawEndsAt() == null
                    ? issue.bettingEndsAt() : issue.drawEndsAt().minusSeconds(schedule.closeSeconds());
            Instant effectiveCloseAt = machineCloseAt.isBefore(issue.bettingEndsAt())
                    ? machineCloseAt : issue.bettingEndsAt();
            long secondsLeft = Duration.between(now, effectiveCloseAt).getSeconds();
            boolean reminderWindow = secondsLeft > 0 && secondsLeft <= 60;
            boolean closureDue = schedule.closeSeconds() > 0
                    && machineCloseAt.isBefore(issue.bettingEndsAt()) && !now.isBefore(effectiveCloseAt);
            if (!reminderWindow && !closureDue) continue;
            Boolean created = transactions.execute(status -> enqueueMachineCloseNotices(
                    schedule.agentId(), issue.issueNumber(), now));
            if (Boolean.TRUE.equals(created)) enqueued++;
        }
        return enqueued;
    }

    private boolean enqueueMachineCloseNotices(long agentId, String issueNumber, Instant now) {
        if (!agents.lockMachineForNotice(agentId)) return false;
        AgentRepository.MachineCloseSchedule current = agents.findAu8MachineCloseSchedule(agentId).orElse(null);
        var currentIssue = games.findCurrentIssue("AU8").orElse(null);
        if (current == null || currentIssue == null || !current.manualBettingOpen()
                || !"BETTING".equals(currentIssue.phase())
                || !currentIssue.issueNumber().equals(issueNumber)
                || currentIssue.bettingEndsAt() == null) return false;
        Instant machineCloseAt = current.closeSeconds() <= 0 || currentIssue.drawEndsAt() == null
                ? currentIssue.bettingEndsAt()
                : currentIssue.drawEndsAt().minusSeconds(current.closeSeconds());
        Instant closeAt = machineCloseAt.isBefore(currentIssue.bettingEndsAt())
                ? machineCloseAt : currentIssue.bettingEndsAt();
        long secondsLeft = Duration.between(now, closeAt).getSeconds();
        boolean reminderWindow = secondsLeft > 0 && secondsLeft <= 60;
        boolean earlyClose = current.closeSeconds() > 0 && machineCloseAt.isBefore(currentIssue.bettingEndsAt());
        boolean closureDue = earlyClose && !now.isBefore(closeAt);
        if (!reminderWindow && !closureDue) return false;
        boolean created = false;
        if (reminderWindow && now.isBefore(closeAt) && secondsLeft > 0) {
            String key = "MACHINE-CLOSE-60-" + issueNumber + "-" + agentId;
            if (!intents.existsByKey(key)) {
                created |= noticeIntents.enqueue("MACHINE_CLOSE_WARNING", "GAME_ISSUE_MACHINE", issueNumber + ":" + agentId,
                        "AU8", issueNumber, ChatMessageVisibility.MACHINE, agentId, "MACHINE_CLOSE_WARNING",
                        "距离" + issueNumber + "期本机封盘还剩" + secondsLeft + "秒！", key, now);
            }
        }
        if (closureDue && earlyClose && !now.isBefore(closeAt)) {
            String stopKey = "MACHINE-CLOSE-STOP-" + issueNumber + "-" + agentId;
            created |= noticeIntents.enqueue("MACHINE_BETTING_CLOSED", "GAME_ISSUE_MACHINE", issueNumber + ":" + agentId,
                    "AU8", issueNumber, ChatMessageVisibility.MACHINE, agentId, "MACHINE_BETTING_CLOSED",
                    issueNumber + "期本机已停止下注", stopKey, now);
            String checklist = machineAuditText(issueNumber, agentId);
            created |= noticeIntents.enqueue("MACHINE_BETTING_AUDIT", "GAME_ISSUE_MACHINE", issueNumber + ":" + agentId,
                    "AU8", issueNumber, ChatMessageVisibility.MACHINE, agentId, "MACHINE_BETTING_AUDIT",
                    checklist, "MACHINE-CLOSE-AUDIT-" + issueNumber + "-" + agentId, now);
        }
        return created;
    }

    private String machineAuditText(String issueNumber, long agentId) {
        var bets = games.findBetAuditByGameIssueAndAgent(GameDataRepository.DEFAULT_GAME_CODE,
                issueNumber, agentId);
        var total = bets.stream().map(GameDataRepository.BetAuditRecord::stake)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        StringBuilder content = new StringBuilder("-----------\n").append(issueNumber)
                .append("\n核对列表:(").append(total.setScale(2, java.math.RoundingMode.HALF_UP))
                .append(")\n");
        var grouped = bets.stream().collect(Collectors.groupingBy(
                bet -> normalizeName(bet.displayName()), java.util.LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((name, playerBets) -> {
            String formatted = playerBets.stream().map(bet -> "第" + bet.ballNumber() + "球/"
                    + BetCommandFormatter.format(bet.playType(), bet.parameters(), bet.stake()))
                    .collect(Collectors.joining("，"));
            content.append('(').append(name).append(") \"").append(formatted).append("\"\n");
        });
        return content.append("-----------\n以核对列表及开奖前已公告退单为准；不在列表内的注单无效。").toString();
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) return "匿名用户";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    public int dispatchDue(Instant now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("机器人通知派发参数无效");
        }
        int processed = 0;
        List<Long> dueIds = intents.findDueIds(now, batchSize);
        for (Long id : dueIds) {
            try {
                Boolean handled = transactions.execute(status -> dispatchOne(id, now));
                if (Boolean.TRUE.equals(handled)) processed++;
            } catch (RuntimeException failure) {
                markFailed(id, failure, now);
            }
        }
        return processed;
    }

    private boolean dispatchOne(long id, Instant now) {
        RobotNoticeIntent intent = intents.findForUpdate(id).orElse(null);
        if (intent == null || isTerminal(intent.status())) return false;
        if ("PROCESSING".equals(intent.status()) && intent.lockedUntil() != null
                && intent.lockedUntil().isAfter(now)) return false;
        if (intent.attemptCount() >= MAX_ATTEMPTS) {
            intents.markSkipped(id, "MAX_ATTEMPTS_EXCEEDED", now);
            return true;
        }
        if (!intents.markProcessing(id, now.plusSeconds(30), now)) return false;

        if (intent.visibility() == ChatMessageVisibility.MACHINE
                && !intents.machineExists(intent.agentId())) {
            intents.markSkipped(id, "MACHINE_NOT_AVAILABLE", now);
            return true;
        }
        ChatRobot robot = robots.findPrimaryRobot().orElse(null);
        if (robot == null || !robot.isEnabled()) {
            intents.markSkipped(id, "ROBOT_NOT_AVAILABLE", now);
            return true;
        }
        ChatMessage legacyMessage = chatMessages.findByIdempotencyKey(intent.idempotencyKey()).orElse(null);
        if (legacyMessage != null) {
            boolean sameDelivery = legacyMessage.status() == ChatMessageStatus.ACTIVE
                    && legacyMessage.senderType() == ChatSenderType.ROBOT
                    && java.util.Objects.equals(legacyMessage.content(), intent.content())
                    && java.util.Objects.equals(legacyMessage.issueNumber(), intent.issueNumber())
                    && legacyMessage.visibility() == intent.visibility()
                    && java.util.Objects.equals(legacyMessage.agentId(), intent.agentId());
            if (sameDelivery) intents.markPublished(id, legacyMessage.id(), now);
            else intents.markSkipped(id, "IDEMPOTENCY_KEY_ALREADY_USED", now);
            return true;
        }
        var message = messages.publishRobotMessage(robot.id(), robot.displayName(), "main",
                intent.issueNumber(), intent.idempotencyKey(), intent.content(), intent.payloadJson(),
                intent.visibility(), intent.agentId(), now);
        if (intents.markPublished(id, message.id(), now) != 1) {
            throw new IllegalStateException("机器人通知意图状态更新失败");
        }
        return true;
    }

    private void markFailed(long id, RuntimeException failure, Instant now) {
        try {
            transactions.execute(status -> {
                RobotNoticeIntent intent = intents.findForUpdate(id).orElse(null);
                if (intent == null || isTerminal(intent.status())) return false;
                if (intent.attemptCount() >= MAX_ATTEMPTS) {
                    intents.markSkipped(id, "MAX_ATTEMPTS_EXCEEDED:" + safeError(failure), now);
                    return true;
                }
                int nextAttempt = intent.attemptCount() + 1;
                long delaySeconds = Math.min(300, 1L << Math.min(nextAttempt, 8));
                intents.markFailed(id, now.plusSeconds(delaySeconds), safeError(failure), now);
                return true;
            });
        } catch (RuntimeException persistenceFailure) {
            log.error("机器人通知失败状态写入失败 intentId={}", id, persistenceFailure);
        }
    }

    private static boolean isTerminal(String status) {
        return "PUBLISHED".equals(status) || "SKIPPED".equals(status);
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ":" + message);
        value = value.replace('\r', ' ').replace('\n', ' ').strip();
        return value.length() <= 240 ? value : value.substring(0, 240);
    }
}
