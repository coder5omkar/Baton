package com.acme.exchange.api.web.dto;

public record RenewRequest(
        Integer leaseSeconds
) {
}
