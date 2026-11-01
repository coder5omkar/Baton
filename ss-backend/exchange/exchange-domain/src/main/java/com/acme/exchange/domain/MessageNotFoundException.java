package com.acme.exchange.domain;

public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(String messageId) {
        super("Message " + messageId + " was not found");
    }
}
