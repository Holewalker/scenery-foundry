package com.product.piecesexport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PiecesManifestWriterTest {
    private static final UUID GROUP_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID PROJECT_ID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    private static final UUID ASSET_ID = UUID.fromString("423e4567-e89b-12d3-a456-426614174000");

    @Test
    void writesTheFrozenPiecesExportV1GoldenWithQuantityAndGeometryStatusCarried() {
        PiecesManifestWriter writer = new PiecesManifestWriter();
        PiecesManifest manifest = new PiecesManifest(GROUP_ID, PROJECT_ID, Instant.parse("2026-08-21T07:01:02.123456Z"),
            List.of(new PiecesManifest.Piece(ASSET_ID, "pieces/" + ASSET_ID + ".stl",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "VALID_VOLUME", 3)));

        byte[] raw = writer.write(manifest);

        assertThat(new String(raw, StandardCharsets.UTF_8)).isEqualTo("""
            {"contract":"scenery-foundry.pieces-export/v1","version":1,"printGroupId":"123e4567-e89b-12d3-a456-426614174000",\
            "projectId":"223e4567-e89b-12d3-a456-426614174000","generatedAt":"2026-08-21T07:01:02.123456Z","pieces":[\
            {"assetId":"423e4567-e89b-12d3-a456-426614174000","fileName":"pieces/423e4567-e89b-12d3-a456-426614174000.stl",\
            "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","geometryStatus":"VALID_VOLUME","quantity":3}]}""");
    }

    @Test
    void ordersPiecesByAscendingAssetIdRegardlessOfInputOrder() {
        PiecesManifestWriter writer = new PiecesManifestWriter();
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PiecesManifest manifest = new PiecesManifest(GROUP_ID, PROJECT_ID, Instant.parse("2026-08-21T07:01:02.123456Z"), List.of(
            piece(high, 1), piece(low, 2)));

        String raw = new String(writer.write(manifest), StandardCharsets.UTF_8);

        assertThat(raw.indexOf("\"assetId\":\"" + low + "\"")).isLessThan(raw.indexOf("\"assetId\":\"" + high + "\""));
    }

    @Test
    void carriesDifferentGeometryStatusesAndQuantitiesForDifferentPieces() {
        PiecesManifestWriter writer = new PiecesManifestWriter();
        UUID second = UUID.fromString("523e4567-e89b-12d3-a456-426614174000");
        PiecesManifest manifest = new PiecesManifest(GROUP_ID, PROJECT_ID, Instant.parse("2026-08-21T07:01:02.123456Z"), List.of(
            new PiecesManifest.Piece(ASSET_ID, "pieces/" + ASSET_ID + ".stl", "a".repeat(64), "INVALID_VOLUME", 1),
            new PiecesManifest.Piece(second, "pieces/" + second + ".stl", "b".repeat(64), "UNKNOWN", 5)));

        String raw = new String(writer.write(manifest), StandardCharsets.UTF_8);

        assertThat(raw).contains("\"geometryStatus\":\"INVALID_VOLUME\",\"quantity\":1")
            .contains("\"geometryStatus\":\"UNKNOWN\",\"quantity\":5");
    }

    private PiecesManifest.Piece piece(UUID assetId, int quantity) {
        return new PiecesManifest.Piece(assetId, "pieces/" + assetId + ".stl", "a".repeat(64), "VALID_VOLUME", quantity);
    }
}
