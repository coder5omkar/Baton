package com.acme.exchange.api.web.dto;

public record SettleRequest(
        String outcome,
        String errorCode
) {
}
