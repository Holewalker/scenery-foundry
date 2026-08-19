package com.product.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.product.scene.AssetGeometryStatus;
import com.product.scene.AssetProcessingStatus;
import com.product.scene.InvalidSceneException;
import com.product.scene.PreparedAsset;

class AssetCatalogEntryTest {
    @Test
    void acceptsEveryProcessingAndGeometryStatusCombination() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        for (AssetProcessingStatus processing : AssetProcessingStatus.values()) {
            for (AssetGeometryStatus geometry : AssetGeometryStatus.values()) {
                var entry = new AssetCatalogEntry(id, owner, processing, geometry, "assets/a.stl", null, null, null, null);
                assertThat(entry.processingStatus()).isEqualTo(processing);
                assertThat(entry.geometryStatus()).isEqualTo(geometry);
            }
        }
    }

    @Test
    void acceptsStatesThatPreparedAssetWouldRejectUnlikePreparedAsset() {
        UUID id = UUID.randomUUID();
        var entry = new AssetCatalogEntry(id, UUID.randomUUID(), AssetProcessingStatus.UPLOADED, AssetGeometryStatus.UNKNOWN, null, null, null, null, null);
        assertThat(entry.processingStatus()).isEqualTo(AssetProcessingStatus.UPLOADED);
        assertThatThrownBy(() -> new PreparedAsset(id, UUID.randomUUID(), AssetProcessingStatus.UPLOADED,
            AssetGeometryStatus.UNKNOWN, "k", "a".repeat(64))).isInstanceOf(InvalidSceneException.class);
    }

    @Test
    void rejectsMissingIdentityOrStatusFields() {
        assertThatThrownBy(() -> new AssetCatalogEntry(null, UUID.randomUUID(), AssetProcessingStatus.UPLOADED,
            AssetGeometryStatus.UNKNOWN, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetCatalogEntry(UUID.randomUUID(), null, AssetProcessingStatus.UPLOADED,
            AssetGeometryStatus.UNKNOWN, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
