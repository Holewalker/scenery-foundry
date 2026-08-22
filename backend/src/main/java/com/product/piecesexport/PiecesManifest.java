package com.product.piecesexport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** In-memory model for the {@code scenery-foundry.pieces-export/v1} manifest contract (design's Interfaces section). */
public record PiecesManifest(UUID printGroupId, UUID projectId, Instant generatedAt, List<Piece> pieces) {
    public PiecesManifest {
        if (printGroupId == null || projectId == null || generatedAt == null || pieces == null) {
            throw new IllegalArgumentException("pieces-export manifest identity is required");
        }
        pieces = List.copyOf(pieces);
    }

    public record Piece(UUID assetId, String fileName, String sha256, String geometryStatus, int quantity) {
        public Piece {
            if (assetId == null || fileName == null || fileName.isBlank() || sha256 == null || sha256.isBlank()
                || geometryStatus == null || geometryStatus.isBlank() || quantity < 1) {
                throw new IllegalArgumentException("pieces-export piece is invalid");
            }
        }
    }
}
