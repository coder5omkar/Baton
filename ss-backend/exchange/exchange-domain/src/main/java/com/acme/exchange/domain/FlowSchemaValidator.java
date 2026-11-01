package com.acme.exchange.domain;

import tools.jackson.databind.JsonNode;

/**
 * Validates an accepted CloudEvents envelope against the immutable JSON Schema
 * that belongs to the flow's active revision.
 */
public interface FlowSchemaValidator {

    void validate(String schemaJson, JsonNode envelope);
}
