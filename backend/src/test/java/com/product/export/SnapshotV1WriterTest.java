package com.product.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SnapshotV1WriterTest {
    private static final UUID EXPORT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID PROJECT_ID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    private static final UUID OWNER_ID = UUID.fromString("323e4567-e89b-12d3-a456-426614174000");
    private static final UUID ASSET_ID = UUID.fromString("423e4567-e89b-12d3-a456-426614174000");

    @Test
    void writesTheFrozenSnapshotV1GoldenWithRequiredLexemes() {
        SnapshotV1Writer writer = new SnapshotV1Writer();

        byte[] raw = writer.write(snapshot());

        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain("idempotency").isEqualTo("""
            {"snapshot_version":1,"export_id":"123e4567-e89b-12d3-a456-426614174000","project_id":"223e4567-e89b-12d3-a456-426614174000","owner_id":"323e4567-e89b-12d3-a456-426614174000","captured_at":"2026-08-14T07:01:02.123456Z","objects":[{"scene_object_id":7,"asset_id":"423e4567-e89b-12d3-a456-426614174000","original_storage_key":"originals/cube.stl","original_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","matrix_contract_version":1,"matrix_world_column_major":[1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,12.5,0.0,0.0,1.0]}],"options":{"boolean_engine":"manifold3d","boolean_engine_version":"3.5.2","geometry_policy_version":"scenery-foundry.geometry-policy/v1","requested_output_format":"stl-binary"}}""".trim());
    }

    @Test
    void canonicalizesTheExactRawBytesThroughTheExistingJcsContract() {
        SnapshotV1Writer writer = new SnapshotV1Writer();

        SnapshotV1Writer.CanonicalSnapshot result = writer.canonicalize(snapshot());

        assertThat(result.contract()).isEqualTo("scenery-foundry.snapshot-jcs/v1");
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
        assertThat(result.canonicalBytes()).isNotEmpty();
        assertThat(result.canonicalBytes()).isNotEqualTo(writer.write(snapshot()));
    }

    @Test
    void ordersObjectsByAscendingSceneObjectId() {
        SnapshotV1Writer writer = new SnapshotV1Writer();
        SnapshotV1 unordered = new SnapshotV1(EXPORT_ID, PROJECT_ID, OWNER_ID, Instant.parse("2026-08-14T07:01:02.123456Z"), List.of(
            object(9), object(2)));

        String raw = new String(writer.write(unordered), StandardCharsets.UTF_8);

        assertThat(raw.indexOf("scene_object_id\":2")).isLessThan(raw.indexOf("scene_object_id\":9"));
    }

    private SnapshotV1 snapshot() {
        return new SnapshotV1(EXPORT_ID, PROJECT_ID, OWNER_ID, Instant.parse("2026-08-14T07:01:02.123456Z"), List.of(object(7)));
    }

    private SnapshotV1.ObjectSnapshot object(long sceneObjectId) {
        return new SnapshotV1.ObjectSnapshot(sceneObjectId, ASSET_ID, "originals/cube.stl",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1,
            new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 12.5, 0, 0, 1});
    }
}



