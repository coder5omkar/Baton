package com.acme.exchange.domain;

import com.acme.platform.hash.Hashing;
import com.acme.platform.json.CanonicalJson;
import tools.jackson.databind.JsonNode;

public final class CanonicalHasher {

    private CanonicalHasher() {
    }

    public static String hash(JsonNode envelope) {
        return Hashing.sha256Hex(CanonicalJson.canonicalize(envelope));
    }
}
