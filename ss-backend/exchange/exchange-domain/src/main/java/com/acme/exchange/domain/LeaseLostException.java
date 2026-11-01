package com.acme.exchange.domain;

import java.util.UUID;

public class LeaseLostException extends RuntimeException {

    private final UUID deliveryId;

    public LeaseLostException(UUID deliveryId) {
        super("The delivery lease is no longer current");
        this.deliveryId = deliveryId;
    }

    public UUID deliveryId() {
        return deliveryId;
    }
}
