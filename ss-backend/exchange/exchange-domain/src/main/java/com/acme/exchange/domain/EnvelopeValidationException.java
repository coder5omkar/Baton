package com.acme.exchange.domain;

public class EnvelopeValidationException extends RuntimeException {

    public EnvelopeValidationException(String message) {
        super(message);
    }
}
