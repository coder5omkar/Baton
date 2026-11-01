package com.acme.exchange.domain;

import java.time.Instant;

public record PublicationStatus(
        String messageId,
        String state,
        Instant acceptedAt,
        Instant publishedAt,
        int expectedSubscribers
) {
}
