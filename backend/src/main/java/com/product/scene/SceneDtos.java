package com.product.scene;

import java.util.List;
import java.util.UUID;

public final class SceneDtos {
    private SceneDtos() { }

    public record AssetSummaryDto(UUID id) { }

    public record SceneObjectDto(
        long id, UUID assetId, int matrixContractVersion,
        double[] translationMm, double[] quaternionXyzw, double[] scale, double[] matrixWorldColumnMajor,
        UUID printGroupId, UUID levelId) {

        /** Convenience constructor for callers that don't assign a print group or level (both nullable, D6). */
        public SceneObjectDto(long id, UUID assetId, int matrixContractVersion,
                double[] translationMm, double[] quaternionXyzw, double[] scale, double[] matrixWorldColumnMajor) {
            this(id, assetId, matrixContractVersion, translationMm, quaternionXyzw, scale, matrixWorldColumnMajor, null, null);
        }
    }

    public record SceneDto(List<SceneObjectDto> objects) { }
}
