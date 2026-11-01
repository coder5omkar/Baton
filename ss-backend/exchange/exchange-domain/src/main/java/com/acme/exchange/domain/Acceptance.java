package com.acme.exchange.domain;

import java.time.Instant;

public record Acceptance(
        String messageId,
        Instant acceptedAt,
        boolean replayed
) {
}
