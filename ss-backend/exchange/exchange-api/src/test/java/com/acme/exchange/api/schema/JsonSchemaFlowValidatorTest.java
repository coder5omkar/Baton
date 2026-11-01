package com.acme.exchange.api.schema;

import com.acme.exchange.domain.SchemaValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaFlowValidatorTest {

    private static final String SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "required": ["data"],
              "properties": {
                "data": {
                  "type": "object",
                  "required": ["orderId"],
                  "properties": {
                    "orderId": {"type": "string"},
                    "amount": {"type": "number"}
                  }
                }
              }
            }
            """;

    private final JsonSchemaFlowValidator validator = new JsonSchemaFlowValidator();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void acceptsAnEnvelopeThatMatchesTheSchema() {
        assertThatCode(() -> validator.validate(
                SCHEMA, envelope("{\"orderId\":\"123\",\"amount\":42.5}")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEnvelopeThatDoesNotMatchTheSchema() {
        assertThatThrownBy(() -> validator.validate(
                SCHEMA, envelope("{\"amount\":42.5}")))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("does not match")
                .satisfies(exception -> assertThat(
                        ((SchemaValidationException) exception).errors()).isNotEmpty());
    }

    @Test
    void rejectsAnInvalidSchema() {
        assertThatThrownBy(() -> validator.validate(
                "{\"type\":", envelope("{\"orderId\":\"123\"}")))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("Flow schema is invalid");
    }

    private JsonNode envelope(String data) {
        return mapper.readTree("""
                {
                  "specversion": "1.0",
                  "id": "msg-1",
                  "source": "test",
                  "type": "order.created",
                  "data": %s
                }
                """.formatted(data));
    }
}
