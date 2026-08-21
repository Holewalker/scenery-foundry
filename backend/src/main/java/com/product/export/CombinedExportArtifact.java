package com.product.export;

/** Only ever populated for a COMBINED_EXPORT job that {@link com.product.geometryjob.CombinedExportProjector}
 * has verified and stamped {@code projected_at} on — see {@link JdbcCombinedExportRepository#findArtifact}. */
public record CombinedExportArtifact(String storageKey, String sha256) { }
