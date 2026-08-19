package com.product.asset;

import java.util.UUID;

import com.product.scene.AssetGeometryStatus;
import com.product.scene.AssetProcessingStatus;

/** Non-leaking API shape for {@code /api/assets*}: no {@code ownerId} (always the caller), no storage keys
 * (internal layout with no consumer) — {@code previewStorageKey} is replaced by the one fact the client
 * actually needs from it. */
public record AssetResponse(UUID id, AssetProcessingStatus processingStatus, AssetGeometryStatus geometryStatus,
                            Long triangleCount, String errorCode, boolean previewAvailable) {

    public static AssetResponse from(AssetCatalogEntry entry) {
        return new AssetResponse(entry.id(), entry.processingStatus(), entry.geometryStatus(),
            entry.triangleCount(), entry.errorCode(),
            entry.processingStatus() == AssetProcessingStatus.READY && entry.previewStorageKey() != null);
    }
}
