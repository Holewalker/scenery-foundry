package com.product.export;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record SnapshotV1(UUID exportId, UUID projectId, UUID ownerId, Instant capturedAt, List<ObjectSnapshot> objects) {
    public SnapshotV1 {
        if (exportId == null || projectId == null || ownerId == null || capturedAt == null
            || objects == null) {
            throw new IllegalArgumentException("snapshot-v1 identity is required");
        }
        objects = List.copyOf(objects);
    }

    public record ObjectSnapshot(long sceneObjectId, UUID assetId, String originalStorageKey,
                                 String originalSha256, int matrixContractVersion,
                                 double[] matrixWorldColumnMajor) {
        public ObjectSnapshot {
            if (sceneObjectId < 1 || sceneObjectId > 9_007_199_254_740_991L || assetId == null
                || originalStorageKey == null || originalStorageKey.isBlank()
                || originalSha256 == null || !originalSha256.matches("[0-9a-f]{64}")
                || matrixContractVersion != 1 || matrixWorldColumnMajor == null
                || matrixWorldColumnMajor.length != 16) {
                throw new IllegalArgumentException("snapshot-v1 object is invalid");
            }
            matrixWorldColumnMajor = Arrays.copyOf(matrixWorldColumnMajor, 16);
            for (double value : matrixWorldColumnMajor) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("matrix must be finite");
            }
        }

        @Override
        public double[] matrixWorldColumnMajor() {
            return Arrays.copyOf(matrixWorldColumnMajor, matrixWorldColumnMajor.length);
        }
    }
}


