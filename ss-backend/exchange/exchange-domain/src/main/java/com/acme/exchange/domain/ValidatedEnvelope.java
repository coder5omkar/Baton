package com.acme.exchange.domain;

public record ValidatedEnvelope(
        String messageId,
        String contentHash,
        int sizeBytes
) {
}
