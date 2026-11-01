package com.acme.exchange.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record AcceptedMessage(
        String tenantId,
        String flowId,
        String messageId,
        long revision,
        String contentHash,
        JsonNode envelope,
        long routeRevision,
        Instant acceptedAt,
        Instant expiresAt
) {
}
