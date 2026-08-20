package com.product.piecesexport;

import java.util.List;

/** Everything {@link PiecesExportController} needs to stream the ZIP: the already-serialized manifest bytes
 * and the ordered list of distinct-asset files to read from storage. All validation and cap enforcement has
 * already happened by the time a {@code PiecesExportPlan} exists (D5: nothing streams unless fully eligible). */
public record PiecesExportPlan(byte[] manifestJson, List<PieceFile> pieces) {
    public PiecesExportPlan {
        manifestJson = manifestJson.clone();
        pieces = List.copyOf(pieces);
    }

    @Override
    public byte[] manifestJson() { return manifestJson.clone(); }

    public record PieceFile(String fileName, String originalStorageKey) { }
}
