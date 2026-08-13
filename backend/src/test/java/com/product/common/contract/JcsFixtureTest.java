package com.product.common.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JcsFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalizesEverySharedValidCaseThroughTheProductionAdapter() throws Exception {
        JsonNode fixture = fixture();
        SnapshotJcsCanonicalizer canonicalizer = new SnapshotJcsCanonicalizer();

        assertThat(fixture.get("contract").stringValue()).isEqualTo("scenery-foundry.snapshot-jcs/v1");
        for (JsonNode testCase : fixture.get("validCases")) {
            SnapshotJcsCanonicalizer.CanonicalResult result = canonicalizer.canonicalize(
                HexFormat.of().parseHex(testCase.get("rawUtf8Hex").stringValue()));

            assertThat(HexFormat.of().formatHex(result.canonicalBytes()))
                .isEqualTo(testCase.get("canonicalUtf8Hex").stringValue());
            assertThat(result.sha256()).isEqualTo(testCase.get("sha256").stringValue());
            assertThat(result.contract()).isEqualTo("scenery-foundry.snapshot-jcs/v1");
        }
    }

    @Test
    void rejectsEverySharedInvalidCaseBeforeProducingAResult() throws Exception {
        SnapshotJcsCanonicalizer canonicalizer = new SnapshotJcsCanonicalizer();
        for (JsonNode testCase : fixture().get("invalidCases")) {
            assertThatThrownBy(() -> canonicalizer.canonicalize(
                HexFormat.of().parseHex(testCase.get("rawUtf8Hex").stringValue())))
                .isInstanceOf(SnapshotJcsCanonicalizer.CanonicalizationException.class)
                .extracting(error -> ((SnapshotJcsCanonicalizer.CanonicalizationException) error).code())
                .isEqualTo(testCase.get("error").stringValue());
        }
    }

    private JsonNode fixture() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/contracts/fixtures/jcs-v1.json")) {
            assertThat(stream).isNotNull();
            return objectMapper.readTree(stream);
        }
    }
}
