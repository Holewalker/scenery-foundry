package com.product.scene;

import java.util.List;
import java.util.UUID;

public final class SceneDtos {
    private SceneDtos() { }

    public record AssetSummaryDto(UUID id) { }

    public record SceneObjectDto(
        long id, UUID assetId, int matrixContractVersion,
        double[] translationMm, double[] quaternionXyzw, double[] scale, double[] matrixWorldColumnMajor) { }

    public record SceneDto(List<SceneObjectDto> objects) { }
}
