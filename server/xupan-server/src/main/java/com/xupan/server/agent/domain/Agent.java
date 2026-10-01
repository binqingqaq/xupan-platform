package com.xupan.server.agent.domain;

import java.time.Instant;

public record Agent(long id, String code, String displayName, Long groupId,
                    Long accountUserId, boolean systemOwned, String status,
                    Long createdBy, Instant createdAt, Instant updatedAt) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
