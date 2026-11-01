package com.acme.platform.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void sortsObjectKeysRecursively() {
        JsonNode first = mapper.readTree("{\"b\":1,\"a\":{\"d\":2,\"c\":3}}");
        JsonNode second = mapper.readTree("{\"a\":{\"c\":3,\"d\":2},\"b\":1}");

        assertThat(CanonicalJson.canonicalize(first))
                .isEqualTo("{\"a\":{\"c\":3,\"d\":2},\"b\":1}");
        assertThat(CanonicalJson.canonicalize(first))
                .isEqualTo(CanonicalJson.canonicalize(second));
    }

    @Test
    void preservesArrayOrder() {
        JsonNode first = mapper.readTree("{\"items\":[1,2,3]}");
        JsonNode second = mapper.readTree("{\"items\":[3,2,1]}");

        assertThat(CanonicalJson.canonicalize(first))
                .isNotEqualTo(CanonicalJson.canonicalize(second));
    }

    @Test
    void normalizesDecimalTrailingZeros() {
        JsonNode first = mapper.readTree("{\"amount\":42.50}");
        JsonNode second = mapper.readTree("{\"amount\":42.5}");

        assertThat(CanonicalJson.canonicalize(first))
                .isEqualTo(CanonicalJson.canonicalize(second));
    }

    @Test
    void keepsNullsAndNestedArraysStable() {
        JsonNode first = mapper.readTree("{\"a\":[{\"z\":null,\"y\":true}],\"b\":null}");
        JsonNode second = mapper.readTree("{\"b\":null,\"a\":[{\"y\":true,\"z\":null}]}");

        assertThat(CanonicalJson.canonicalize(first))
                .isEqualTo(CanonicalJson.canonicalize(second));
    }
}
