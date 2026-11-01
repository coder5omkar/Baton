package com.acme.exchange.domain;

import java.time.Instant;
import java.util.UUID;

public record DeliverySettlement(
        UUID deliveryId,
        String state,
        Instant nextAttemptAt,
        int attempt
) {
}
