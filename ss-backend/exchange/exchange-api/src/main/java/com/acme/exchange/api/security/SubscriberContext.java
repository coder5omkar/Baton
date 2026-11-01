package com.acme.exchange.api.security;

import com.acme.exchange.domain.SubscriberNotBoundException;

public final class SubscriberContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SubscriberContext() {
    }

    public static void set(String subscriberId) {
        CURRENT.set(subscriberId);
    }

    public static String require() {
        String subscriberId = CURRENT.get();
        if (subscriberId == null || subscriberId.isBlank()) {
            throw new SubscriberNotBoundException();
        }
        return subscriberId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
