package com.product.asset;

import java.util.UUID;

import com.product.scene.AssetProcessingStatus;

/** Response shape for {@code POST /api/assets}: server-generated identifiers plus current status. */
public record AssetIntakeResult(UUID assetId, AssetProcessingStatus processingStatus, UUID jobId) { }
