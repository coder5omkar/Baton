package com.acme.exchange.domain;

import tools.jackson.databind.JsonNode;

public record PublishCommand(
        String tenantId,
        String flowId,
        Long revision,
        JsonNode envelope
) {

    public PublishCommand {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("flowId is required");
        }
        if (envelope == null) {
            throw new IllegalArgumentException("envelope is required");
        }
    }
}
