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

    /** {@code version} means "the version I last observed" on a request and "the version now stored" on a
     * response (ADR-0007 D4) — one field serves both directions, so the client always echoes exactly what
     * the server last returned. */
    public record SceneDto(Long version, List<SceneObjectDto> objects) {
        /** Convenience constructor for a transitional client that never sends a version. */
        public SceneDto(List<SceneObjectDto> objects) { this(null, objects); }
    }
}
