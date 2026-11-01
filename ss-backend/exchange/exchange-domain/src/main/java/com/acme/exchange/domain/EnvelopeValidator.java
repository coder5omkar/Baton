package com.acme.exchange.domain;

import tools.jackson.databind.JsonNode;

public interface EnvelopeValidator {

    ValidatedEnvelope validate(JsonNode envelope, int maxInlineBytes);
}
