package com.product.scene;

import java.util.UUID;

public record PreparedAsset(UUID id, UUID projectId, AssetProcessingStatus processingStatus,
                            AssetGeometryStatus geometryStatus, String storageKey, String originalSha256) {
    public PreparedAsset {
        if (id == null || projectId == null || processingStatus != AssetProcessingStatus.READY
            || geometryStatus != AssetGeometryStatus.VALID_VOLUME || storageKey == null || storageKey.isBlank()
            || originalSha256 == null || !originalSha256.matches("[0-9a-f]{64}")) {
            throw new InvalidSceneException("prepared asset is not capture-ready");
        }
    }
}
