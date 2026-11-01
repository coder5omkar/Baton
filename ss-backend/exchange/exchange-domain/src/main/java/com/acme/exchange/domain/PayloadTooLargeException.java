package com.acme.exchange.domain;

public class PayloadTooLargeException extends EnvelopeValidationException {

    private final int actualBytes;
    private final int maxBytes;

    public PayloadTooLargeException(int actualBytes, int maxBytes) {
        super("Payload is " + actualBytes + " bytes; the inline limit is " + maxBytes + " bytes");
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    public int actualBytes() {
        return actualBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }
}
