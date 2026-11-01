package com.acme.platform.hash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void computesKnownSha256() {
        assertThat(Hashing.sha256Hex("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}
