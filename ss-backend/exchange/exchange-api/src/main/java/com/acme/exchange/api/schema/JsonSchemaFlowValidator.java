package com.acme.exchange.api.schema;

import com.acme.exchange.domain.FlowSchemaValidator;
import com.acme.exchange.domain.SchemaValidationException;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JSON Schema validation for the immutable flow revision schema.
 *
 * <p>The schema is cached by its normalized JSON text because the active
 * revision is immutable. A schema change creates a new revision and therefore
 * a new cache entry.</p>
 */
public final class JsonSchemaFlowValidator implements FlowSchemaValidator {

    private static final int MAX_REPORTED_ERRORS = 10;

    private final SchemaRegistry registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final ConcurrentMap<String, Schema> schemas = new ConcurrentHashMap<>();

    @Override
    public void validate(String schemaJson, JsonNode envelope) {
        Schema schema;
        try {
            schema = schemas.computeIfAbsent(schemaJson, registry::getSchema);
        } catch (RuntimeException exception) {
            throw new SchemaValidationException(
                    "Flow schema is invalid: " + rootMessage(exception));
        }

        List<Error> errors = schema.validate(envelope);
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream()
                    .map(Error::getMessage)
                    .distinct()
                    .limit(MAX_REPORTED_ERRORS)
                    .toList();
            throw new SchemaValidationException(
                    "Envelope does not match the active flow schema", messages);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
