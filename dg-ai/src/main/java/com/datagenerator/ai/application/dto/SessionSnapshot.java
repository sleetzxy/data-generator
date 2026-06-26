package com.datagenerator.ai.application.dto;

import java.time.Instant;

public record SessionSnapshot(
        String sessionId,
        String agentId,
        String provider,
        Instant createdAt,
        String draftYaml,
        boolean hasDraft,
        boolean draftIncomplete,
        boolean draftValidated) {}
