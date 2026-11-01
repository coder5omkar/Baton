package com.acme.exchange.domain;

public class SubscriberNotBoundException extends RuntimeException {

    public SubscriberNotBoundException() {
        super("The caller is not bound to a subscriber");
    }
}
