package com.acme.exchange.api.web.dto;

public record LeaseRequest(
        Integer maxMessages,
        Integer leaseSeconds
) {
}
