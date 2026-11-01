package com.acme.exchange.domain;

public class StaleRevisionException extends RuntimeException {

    private final long activeRevision;

    public StaleRevisionException(long activeRevision) {
        super("Requested revision is not the active revision (" + activeRevision + ")");
        this.activeRevision = activeRevision;
    }

    public long activeRevision() {
        return activeRevision;
    }
}
