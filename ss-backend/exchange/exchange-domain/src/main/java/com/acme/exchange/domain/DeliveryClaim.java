package com.acme.exchange.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record DeliveryClaim(
        UUID deliveryId,
        Instant leaseExpiresAt,
        int attempt,
        JsonNode envelope
) {
}
