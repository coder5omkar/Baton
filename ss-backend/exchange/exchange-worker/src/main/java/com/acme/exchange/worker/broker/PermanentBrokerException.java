package com.acme.exchange.worker.broker;

public class PermanentBrokerException extends BrokerException {

    public PermanentBrokerException(String message) {
        super(message);
    }

    public PermanentBrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
