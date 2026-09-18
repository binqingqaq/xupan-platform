package com.xupan.server.chat.service;

import com.xupan.server.game.service.BetSettlementCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Publishes settlement feedback only after the game transaction commits. */
@Component
public final class SettlementFeedbackListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementFeedbackListener.class);
    private final ChatMessageService chatMessageService;

    public SettlementFeedbackListener(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementCompleted(BetSettlementCompletedEvent event) {
        try {
            chatMessageService.publishSettlementFeedback(event);
        } catch (RuntimeException exception) {
            // Funds and the bet are already committed; never turn a message failure into a second settlement.
            log.error("结算反馈发布失败 betId={} issueNumber={}", event.betId(), event.issueNumber(), exception);
        }
    }
}
