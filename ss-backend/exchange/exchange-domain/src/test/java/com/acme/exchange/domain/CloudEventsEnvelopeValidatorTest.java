package com.acme.exchange.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudEventsEnvelopeValidatorTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CloudEventsEnvelopeValidator validator = new CloudEventsEnvelopeValidator();

    @Test
    void acceptsAValidEnvelope() {
        JsonNode envelope = mapper.readTree("""
                {
                  "specversion": "1.0",
                  "id": "ord-2026-000123",
                  "source": "billing",
                  "type": "order.created.v2",
                  "data": {"orderId": "123", "amount": 42.5}
                }
                """);

        ValidatedEnvelope result = validator.validate(envelope, 131072);

        assertThat(result.messageId()).isEqualTo("ord-2026-000123");
        assertThat(result.contentHash()).hasSize(64);
        assertThat(result.sizeBytes()).isPositive();
    }

    @Test
    void rejectsAMissingId() {
        JsonNode envelope = mapper.readTree("""
                {"specversion":"1.0","source":"billing","type":"order.created","data":{}}
                """);

        assertThatThrownBy(() -> validator.validate(envelope, 131072))
                .isInstanceOf(EnvelopeValidationException.class)
                .hasMessageContaining("id");
    }

    @Test
    void rejectsAnUnsupportedSpecVersion() {
        JsonNode envelope = mapper.readTree("""
                {"specversion":"0.3","id":"a-1","source":"billing","type":"order.created","data":{}}
                """);

        assertThatThrownBy(() -> validator.validate(envelope, 131072))
                .isInstanceOf(EnvelopeValidationException.class)
                .hasMessageContaining("specversion");
    }

    @Test
    void rejectsAnOversizedPayload() {
        JsonNode envelope = mapper.readTree("""
                {"specversion":"1.0","id":"a-1","source":"billing","type":"order.created","data":{"blob":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}}
                """);

        assertThatThrownBy(() -> validator.validate(envelope, 64))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void sameContentInDifferentKeyOrderProducesTheSameHash() {
        JsonNode first = mapper.readTree("""
                {"specversion":"1.0","id":"a-1","source":"billing","type":"order.created","data":{"b":2,"a":1}}
                """);
        JsonNode second = mapper.readTree("""
                {"data":{"a":1,"b":2},"type":"order.created","source":"billing","id":"a-1","specversion":"1.0"}
                """);

        assertThat(validator.validate(first, 131072).contentHash())
                .isEqualTo(validator.validate(second, 131072).contentHash());
    }
}
