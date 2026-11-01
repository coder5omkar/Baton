package com.acme.exchange.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record DeliveryLease(
        UUID deliveryId,
        String leaseToken,
        Instant leaseExpiresAt,
        int attempt,
        JsonNode envelope
) {
}
