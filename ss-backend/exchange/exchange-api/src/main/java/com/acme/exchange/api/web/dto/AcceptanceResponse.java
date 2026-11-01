package com.acme.exchange.api.web.dto;

public record AcceptanceResponse(
        String messageId,
        String state,
        boolean replayed
) {
}
