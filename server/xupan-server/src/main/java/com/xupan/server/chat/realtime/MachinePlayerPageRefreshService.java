package com.xupan.server.chat.realtime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends a direct page refresh to online players after a machine setting commits. */
@Component
public class MachinePlayerPageRefreshService {

    private final ApplicationEventPublisher eventPublisher;
    private final ChatConnectionRegistry connectionRegistry;
    private final ChatWebSocketProperties properties;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public MachinePlayerPageRefreshService(ApplicationEventPublisher eventPublisher,
                                           ChatConnectionRegistry connectionRegistry,
                                           ChatWebSocketProperties properties,
                                           tools.jackson.databind.ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.connectionRegistry = connectionRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void refreshAfterCommit(long agentId) {
        if (agentId <= 0) {
            throw new IllegalArgumentException("agentId 必须大于 0");
        }
        eventPublisher.publishEvent(new MachineSettingChanged(agentId));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMachineSettingChanged(MachineSettingChanged event) {
        String payload = ChatProtocol.encode(objectMapper, ChatProtocol.playerPageRefresh(),
                properties.getMaxOutboundMessageBytes());
        connectionRegistry.broadcastToMachinePlayers(event.agentId(), payload);
    }

    public record MachineSettingChanged(long agentId) {
    }
}
