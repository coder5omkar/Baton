package com.acme.exchange.domain;

import com.acme.platform.json.CanonicalJson;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal CloudEvents 1.0 validation for the acceptance path.
 *
 * <p>Flow-specific JSON Schema validation is intentionally a separate concern:
 * this validator enforces the transport envelope and the inline payload limit.</p>
 */
public final class CloudEventsEnvelopeValidator implements EnvelopeValidator {

    private static final List<String> REQUIRED_FIELDS =
            List.of("specversion", "id", "source", "type", "data");
    private static final Pattern MESSAGE_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$");

    @Override
    public ValidatedEnvelope validate(JsonNode envelope, int maxInlineBytes) {
        if (envelope == null || !envelope.isObject()) {
            throw new EnvelopeValidationException("Envelope must be a JSON object");
        }
        for (String field : REQUIRED_FIELDS) {
            if (!envelope.has(field)) {
                throw new EnvelopeValidationException("Missing required field: " + field);
            }
        }
        if (!envelope.path("specversion").isTextual()
                || !"1.0".equals(envelope.path("specversion").asText())) {
            throw new EnvelopeValidationException("specversion must be the string 1.0");
        }
        if (!envelope.path("id").isTextual()) {
            throw new EnvelopeValidationException("id must be a string");
        }
        String messageId = envelope.path("id").asText();
        if (!MESSAGE_ID.matcher(messageId).matches()) {
            throw new EnvelopeValidationException(
                    "id must be 1-256 characters of [A-Za-z0-9._:-] and start with an alphanumeric");
        }
        if (!envelope.path("source").isTextual() || envelope.path("source").asText().isBlank()) {
            throw new EnvelopeValidationException("source must be a non-blank string");
        }
        if (!envelope.path("type").isTextual() || envelope.path("type").asText().isBlank()) {
            throw new EnvelopeValidationException("type must be a non-blank string");
        }

        String canonical = CanonicalJson.canonicalize(envelope);
        int size = canonical.getBytes(StandardCharsets.UTF_8).length;
        if (size > maxInlineBytes) {
            throw new PayloadTooLargeException(size, maxInlineBytes);
        }

        return new ValidatedEnvelope(messageId, CanonicalHasher.hash(envelope), size);
    }
}
