package com.product.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

import com.product.common.contract.SnapshotJcsCanonicalizer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

public final class SnapshotV1Writer {
    public static final String BOOLEAN_ENGINE = "manifold3d";
    public static final String BOOLEAN_ENGINE_VERSION = "3.5.2";
    public static final String GEOMETRY_POLICY_VERSION = "scenery-foundry.geometry-policy/v1";
    public static final String REQUESTED_OUTPUT_FORMAT = "stl-binary";
    private static final DateTimeFormatter TIMESTAMP = new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final JsonFactory jsonFactory = new JsonFactory();
    private final SnapshotJcsCanonicalizer canonicalizer;

    public SnapshotV1Writer() { this(new SnapshotJcsCanonicalizer()); }

    SnapshotV1Writer(SnapshotJcsCanonicalizer canonicalizer) { this.canonicalizer = canonicalizer; }

    public byte[] write(SnapshotV1 snapshot) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (JsonGenerator json = jsonFactory.createGenerator(output)) {
                json.writeStartObject();
                number(json, "snapshot_version", 1);
                string(json, "export_id", snapshot.exportId().toString());
                string(json, "project_id", snapshot.projectId().toString());
                string(json, "owner_id", snapshot.ownerId().toString());
                string(json, "captured_at", TIMESTAMP.format(snapshot.capturedAt()));
                json.writeName("objects");
                json.writeStartArray();
                var objects = new ArrayList<>(snapshot.objects());
                objects.sort(java.util.Comparator.comparingLong(SnapshotV1.ObjectSnapshot::sceneObjectId));
                for (SnapshotV1.ObjectSnapshot object : objects) writeObject(json, object);
                json.writeEndArray();
                json.writeName("options");
                json.writeStartObject();
                string(json, "boolean_engine", BOOLEAN_ENGINE);
                string(json, "boolean_engine_version", BOOLEAN_ENGINE_VERSION);
                string(json, "geometry_policy_version", GEOMETRY_POLICY_VERSION);
                string(json, "requested_output_format", REQUESTED_OUTPUT_FORMAT);
                json.writeEndObject();
                json.writeEndObject();
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("snapshot-v1 serialization failed", exception);
        }
    }

    public CanonicalSnapshot canonicalize(SnapshotV1 snapshot) {
        SnapshotJcsCanonicalizer.CanonicalResult result = canonicalizer.canonicalize(write(snapshot));
        return new CanonicalSnapshot(result.contract(), result.canonicalBytes(), result.sha256());
    }

    private void writeObject(JsonGenerator json, SnapshotV1.ObjectSnapshot object) throws IOException {
        json.writeStartObject();
        number(json, "scene_object_id", object.sceneObjectId());
        string(json, "asset_id", object.assetId().toString());
        string(json, "original_storage_key", object.originalStorageKey());
        string(json, "original_sha256", object.originalSha256());
        number(json, "matrix_contract_version", object.matrixContractVersion());
        json.writeName("matrix_world_column_major");
        json.writeStartArray();
        for (double value : object.matrixWorldColumnMajor()) json.writeNumber(value == 0d ? 0d : value);
        json.writeEndArray();
        json.writeEndObject();
    }

    private void string(JsonGenerator json, String field, String value) throws IOException { json.writeName(field); json.writeString(value); }
    private void number(JsonGenerator json, String field, long value) throws IOException { json.writeName(field); json.writeNumber(value); }

    public record CanonicalSnapshot(String contract, byte[] canonicalBytes, String sha256) {
        public CanonicalSnapshot { canonicalBytes = canonicalBytes.clone(); }
        @Override public byte[] canonicalBytes() { return canonicalBytes.clone(); }
    }
}
