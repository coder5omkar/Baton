package com.acme.exchange.domain;

public record SubscriberBinding(
        String subscriberId,
        DeliveryMode deliveryMode,
        boolean required,
        long bindingRevision
) {
}
