package com.acme.exchange.domain;

import java.time.Instant;
import java.util.UUID;

public record DeliveryRenewal(
        UUID deliveryId,
        Instant leaseExpiresAt,
        int attempt
) {
}
