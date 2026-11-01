package com.acme.exchange.worker.broker;

public class BrokerException extends RuntimeException {

    public BrokerException(String message) {
        super(message);
    }

    public BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
