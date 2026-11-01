package com.acme.exchange.worker.broker;

public class TransientBrokerException extends BrokerException {

    public TransientBrokerException(String message) {
        super(message);
    }

    public TransientBrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
