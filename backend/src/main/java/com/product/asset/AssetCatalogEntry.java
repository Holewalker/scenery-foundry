package com.product.asset;

import java.util.UUID;

import com.product.scene.AssetGeometryStatus;
import com.product.scene.AssetProcessingStatus;

/** Owner-scoped catalog covering every state; unlike {@link com.product.scene.PreparedAsset} it has no capture-ready invariant. */
public record AssetCatalogEntry(UUID id, UUID ownerId, AssetProcessingStatus processingStatus,
                                AssetGeometryStatus geometryStatus, String originalStorageKey, String originalSha256,
                                String previewStorageKey, Long triangleCount, String errorCode, String originalFilename) {
    public AssetCatalogEntry {
        if (id == null || ownerId == null || processingStatus == null || geometryStatus == null) {
            throw new IllegalArgumentException("asset catalog entry requires id, ownerId, processingStatus and geometryStatus");
        }
    }

    /** Legacy nine-argument form for callers that don't carry a filename (nullable, additive V9 column). */
    public AssetCatalogEntry(UUID id, UUID ownerId, AssetProcessingStatus processingStatus,
            AssetGeometryStatus geometryStatus, String originalStorageKey, String originalSha256,
            String previewStorageKey, Long triangleCount, String errorCode) {
        this(id, ownerId, processingStatus, geometryStatus, originalStorageKey, originalSha256,
            previewStorageKey, triangleCount, errorCode, null);
    }
}
