package com.xupan.server.agent.domain;

import java.time.Instant;

public record AgentGroup(long id, String code, String displayName, String status,
                         Long createdBy, Instant createdAt, Instant updatedAt) {
}
