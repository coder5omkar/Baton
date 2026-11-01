package com.acme.exchange.domain;

import java.time.Instant;

public class MessageIdConflictException extends RuntimeException {

    private final String messageId;
    private final Instant acceptedAt;

    public MessageIdConflictException(String messageId, Instant acceptedAt) {
        super("Message id " + messageId + " was already accepted with different content at " + acceptedAt);
        this.messageId = messageId;
        this.acceptedAt = acceptedAt;
    }

    public String messageId() {
        return messageId;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }
}
