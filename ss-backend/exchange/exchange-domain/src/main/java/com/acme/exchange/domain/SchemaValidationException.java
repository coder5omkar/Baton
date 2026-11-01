package com.acme.exchange.domain;

import java.util.List;

public class SchemaValidationException extends RuntimeException {

    private final List<String> errors;

    public SchemaValidationException(String message) {
        this(message, List.of());
    }

    public SchemaValidationException(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
