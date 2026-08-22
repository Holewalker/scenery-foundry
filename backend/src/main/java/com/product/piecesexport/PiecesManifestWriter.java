package com.product.piecesexport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

/** Serializes {@code manifest.json} for the pieces-export ZIP (contract {@code scenery-foundry.pieces-export/v1}).
 * Entries are always re-sorted by ascending {@code assetId} here — the writer, not its caller, owns determinism,
 * mirroring {@code SnapshotV1Writer}'s own ordering guarantee. */
public final class PiecesManifestWriter {
    public static final String CONTRACT = "scenery-foundry.pieces-export/v1";
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final JsonFactory jsonFactory = new JsonFactory();

    public byte[] write(PiecesManifest manifest) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (JsonGenerator json = jsonFactory.createGenerator(output)) {
                json.writeStartObject();
                string(json, "contract", CONTRACT);
                number(json, "version", 1);
                string(json, "printGroupId", manifest.printGroupId().toString());
                string(json, "projectId", manifest.projectId().toString());
                string(json, "generatedAt", TIMESTAMP.format(manifest.generatedAt()));
                json.writeName("pieces");
                json.writeStartArray();
                var pieces = new ArrayList<>(manifest.pieces());
                // String comparison, not UUID's natural (signed-long) Comparable order: matches Postgres's
                // byte-order "order by asset_id" and a human reader's dictionary-order expectation of "ASC".
                pieces.sort(Comparator.comparing(piece -> piece.assetId().toString()));
                for (PiecesManifest.Piece piece : pieces) writePiece(json, piece);
                json.writeEndArray();
                json.writeEndObject();
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("pieces-export manifest serialization failed", exception);
        }
    }

    private void writePiece(JsonGenerator json, PiecesManifest.Piece piece) throws IOException {
        json.writeStartObject();
        string(json, "assetId", piece.assetId().toString());
        string(json, "fileName", piece.fileName());
        string(json, "sha256", piece.sha256());
        string(json, "geometryStatus", piece.geometryStatus());
        number(json, "quantity", piece.quantity());
        json.writeEndObject();
    }

    private void string(JsonGenerator json, String field, String value) throws IOException { json.writeName(field); json.writeString(value); }
    private void number(JsonGenerator json, String field, long value) throws IOException { json.writeName(field); json.writeNumber(value); }
}
