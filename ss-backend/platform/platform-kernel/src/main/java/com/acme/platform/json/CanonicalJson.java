package com.acme.platform.json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic JSON serialization used for message content hashing.
 *
 * <p>Object keys are sorted recursively, arrays keep their order, and decimal
 * values are normalized by stripping trailing zeros. This is a pragmatic
 * canonical form; replace it with a full RFC 8785 implementation if the
 * platform must interoperate with other canonicalizers.</p>
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private CanonicalJson() {
    }

    public static String canonicalize(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(sort(node));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Unable to canonicalize JSON", e);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>(node.propertyNames());
            Collections.sort(names);
            for (String name : names) {
                result.set(name, sort(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                result.add(sort(child));
            }
            return result;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.scale() < 0) {
                value = value.setScale(0);
            }
            return DecimalNode.valueOf(value);
        }
        return node;
    }
}
