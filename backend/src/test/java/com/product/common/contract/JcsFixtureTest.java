package com.product.common.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JcsFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fixtureCarriesStableCanonicalBytesAndDigest() throws Exception {
        try (InputStream fixtureStream = getClass().getResourceAsStream("/contracts/fixtures/jcs-v1.json")) {
            assertThat(fixtureStream).isNotNull();
            JsonNode fixture = objectMapper.readTree(fixtureStream);
            byte[] canonical = fixture.get("canonical").stringValue().getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));

            assertThat(fixture.get("contract").stringValue()).isEqualTo("scenery-foundry.snapshot-jcs/v1");
            assertThat(fixture.get("input").isObject()).isTrue();
            assertThat(digest).isEqualTo(fixture.get("sha256").stringValue());
        }
    }
}
