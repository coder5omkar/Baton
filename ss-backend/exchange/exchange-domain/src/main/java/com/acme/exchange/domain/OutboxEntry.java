package com.acme.exchange.domain;

import tools.jackson.databind.JsonNode;

public record OutboxEntry(
        long id,
        String tenantId,
        String flowId,
        String messageId,
        long revision,
        JsonNode envelope,
        int attempt,
        long fence,
        String traceparent
) {
}
