package com.product.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.product.scene.AssetGeometryStatus;
import com.product.scene.AssetProcessingStatus;

class AssetResponseTest {

    @Test
    void fromNeverCarriesOwnerIdOrEitherStorageKeyAndDerivesPreviewAvailableWhenReady() {
        var assetId = UUID.randomUUID();
        var entry = new AssetCatalogEntry(assetId, UUID.randomUUID(), AssetProcessingStatus.READY,
            AssetGeometryStatus.VALID_VOLUME, "assets/" + assetId + "/original.stl", "a".repeat(64),
            "assets/" + assetId + "/preview.glb", 12L, null);

        var response = AssetResponse.from(entry);

        assertThat(response.id()).isEqualTo(assetId);
        assertThat(response.processingStatus()).isEqualTo(AssetProcessingStatus.READY);
        assertThat(response.geometryStatus()).isEqualTo(AssetGeometryStatus.VALID_VOLUME);
        assertThat(response.triangleCount()).isEqualTo(12L);
        assertThat(response.errorCode()).isNull();
        assertThat(response.previewAvailable()).isTrue();
        assertThat(response.toString()).doesNotContain(assetId + "/original.stl").doesNotContain("preview.glb");
    }

    @Test
    void previewAvailableIsFalseWhenNotReadyEvenIfAPreviewKeyIsSomehowPresent() {
        var entry = new AssetCatalogEntry(UUID.randomUUID(), UUID.randomUUID(), AssetProcessingStatus.PROCESSING,
            AssetGeometryStatus.UNKNOWN, "assets/x/original.stl", "b".repeat(64), null, null, null);

        assertThat(AssetResponse.from(entry).previewAvailable()).isFalse();
    }
}
