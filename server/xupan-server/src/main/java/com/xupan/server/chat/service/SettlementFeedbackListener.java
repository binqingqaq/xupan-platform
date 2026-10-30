package com.xupan.server.chat.service;

import com.xupan.server.game.service.BetSettlementCompletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Stores the settlement feedback intent before the settlement transaction commits. */
@Component
public final class SettlementFeedbackListener {

    private final ChatMessageService chatMessageService;

    public SettlementFeedbackListener(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onSettlementCompleted(BetSettlementCompletedEvent event) {
        chatMessageService.enqueueSettlementFeedback(event);
    }
}
