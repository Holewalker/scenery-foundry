package com.product.geometryjob;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for {@link LeaseReconciler}, {@link AssetProjector}, and {@link CombinedExportProjector}.
 * Gated behind {@code app.geometryjob.scheduling.enabled} (default off) so it is never created — and never races
 * a manual {@code reconcile()}/{@code project()} call — inside an ordinary test's {@code ApplicationContext}; the
 * compose deployment turns it on for the real runtime.
 */
@Component
@ConditionalOnProperty(name = "app.geometryjob.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class GeometryJobScheduler {
    private final LeaseReconciler leaseReconciler;
    private final AssetProjector assetProjector;
    private final CombinedExportProjector combinedExportProjector;

    public GeometryJobScheduler(LeaseReconciler leaseReconciler, AssetProjector assetProjector,
            CombinedExportProjector combinedExportProjector) {
        this.leaseReconciler = leaseReconciler;
        this.assetProjector = assetProjector;
        this.combinedExportProjector = combinedExportProjector;
    }

    @Scheduled(fixedDelay = 30000)
    void reconcileExpiredLeases() {
        leaseReconciler.reconcile();
    }

    @Scheduled(fixedDelay = 2000)
    void projectTerminalJobs() {
        assetProjector.project();
        combinedExportProjector.project();
    }
}
