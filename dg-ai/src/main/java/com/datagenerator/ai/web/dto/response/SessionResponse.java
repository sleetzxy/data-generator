package com.datagenerator.ai.web.dto.response;

import java.time.Instant;

public record SessionResponse(
        String sessionId,
        String agentId,
        String provider,
        Instant createdAt) {
}
