package com.product.export;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.product.printgroup.PrintGroupService;
import com.product.storage.StorageAccessException;
import com.product.storage.StorageResolver;

/**
 * Combined Export capture is print-group scoped (Phase 4 design D4/D6): the projection query filters on
 * {@code scene_objects.print_group_id}, not {@code project_id}. Ownership is resolved via {@link PrintGroupService}
 * (authenticate -&gt; resolve ownership -&gt; act, ADR-0003), matching {@code PrintGroupController}'s exact pattern —
 * a foreign or nonexistent print group surfaces as {@link com.product.scene.OwnedResourceNotFoundException} (404).
 *
 * <p>The canonical snapshot is published as {@code exports/{exportId}/snapshot.json} (design: "the snapshot
 * travels to the worker as a published file, not embedded payload") because the worker's DB grant is
 * SELECT/UPDATE on {@code geometry_jobs} only and cannot read {@code export_snapshots} itself. The
 * {@code geometry_jobs} row (job_type=COMBINED_EXPORT, subject_id=exportId, idempotency_key=exportId) is
 * inserted in this SAME {@code @Transactional} method as the snapshot capture: if either insert fails, both
 * roll back together (spec: "Capture fails atomically").
 */
@Service
public class JdbcCaptureProjectionService {
    private static final String JOB_TYPE = "COMBINED_EXPORT";

    private final JdbcClient jdbc;
    private final JdbcExportRepository exports;
    private final PrintGroupService printGroups;
    private final StorageResolver storageResolver;
    private final SnapshotV1Writer writer = new SnapshotV1Writer();

    public JdbcCaptureProjectionService(JdbcClient jdbc, JdbcExportRepository exports, PrintGroupService printGroups,
            StorageResolver storageResolver) {
        this.jdbc = jdbc;
        this.exports = exports;
        this.printGroups = printGroups;
        this.storageResolver = storageResolver;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID capture(UUID ownerId, UUID printGroupId) {
        var group = printGroups.find(ownerId, printGroupId); // 404 (OwnedResourceNotFoundException) for foreign/nonexistent
        lockProject(ownerId, group.projectId());
        List<CaptureObject> projection = jdbc.sql("select o.id,o.asset_id,a.storage_key,a.original_sha256,o.matrix_contract_version,o.matrix_world_column_major,a.processing_status,a.geometry_status from scene_objects o join assets a on a.id=o.asset_id and a.owner_id=o.owner_id where o.print_group_id=:group order by o.id asc")
            .param("group", printGroupId).query((row, index) -> map(row.getLong("id"), row.getObject("asset_id", UUID.class),
                row.getString("storage_key"), row.getString("original_sha256"), row.getInt("matrix_contract_version"), row.getArray("matrix_world_column_major"),
                row.getString("processing_status"), row.getString("geometry_status"))).list();
        if (projection.isEmpty() || projection.size() > 250) throw new InvalidCaptureException("scene cardinality must be 1..250");
        List<SnapshotV1.ObjectSnapshot> objects = projection.stream().map(this::validate).toList();
        UUID exportId = UUID.randomUUID();
        SnapshotV1 snapshot = new SnapshotV1(exportId, group.projectId(), ownerId, Instant.now(), objects);
        SnapshotV1Writer.CanonicalSnapshot canonical = writer.canonicalize(snapshot);
        exports.capture(ownerId, snapshot, canonical);
        publishSnapshot(exportId, canonical);
        insertJob(ownerId, exportId, canonical);
        return exportId;
    }

    private void lockProject(UUID ownerId, UUID projectId) {
        boolean found = jdbc.sql("select id from projects where id=:project and owner_id=:owner for update")
            .param("project", projectId).param("owner", ownerId).query(UUID.class).optional().isPresent();
        if (!found) throw new ExportNotFoundException();
    }

    private SnapshotV1.ObjectSnapshot validate(CaptureObject object) {
        if (!"READY".equals(object.processingStatus()) || !"VALID_VOLUME".equals(object.geometryStatus())) {
            throw new InvalidCaptureException("prepared asset is not capture-ready");
        }
        return new SnapshotV1.ObjectSnapshot(object.sceneObjectId(), object.assetId(), object.storageKey(), object.sha256(),
            object.matrixContractVersion(), object.matrix());
    }

    private CaptureObject map(long id, UUID assetId, String storageKey, String sha256, int matrixContractVersion,
                              java.sql.Array matrix, String processingStatus, String geometryStatus) {
        try {
            Object[] values = (Object[]) matrix.getArray();
            double[] doubles = new double[values.length];
            for (int index = 0; index < values.length; index++) doubles[index] = ((Number) values[index]).doubleValue();
            return new CaptureObject(id, assetId, storageKey, sha256, matrixContractVersion, doubles, processingStatus, geometryStatus);
        } catch (SQLException exception) {
            throw new InvalidCaptureException("scene matrix cannot be read");
        }
    }

    /** Publishes canonical snapshot bytes so the worker (which cannot read {@code export_snapshots}) can fetch
     * them by storage key + sha256, exactly like an ASSET_PROCESSING job's {@code input} shape. */
    private void publishSnapshot(UUID exportId, SnapshotV1Writer.CanonicalSnapshot canonical) {
        String storageKey = "exports/" + exportId + "/snapshot.json";
        var temp = storageResolver.createTempFile();
        try {
            Files.write(temp, canonical.canonicalBytes());
        } catch (IOException exception) {
            throw new StorageAccessException(storageKey);
        }
        storageResolver.publish(temp, storageKey);
    }

    private void insertJob(UUID ownerId, UUID exportId, SnapshotV1Writer.CanonicalSnapshot canonical) {
        UUID jobId = UUID.randomUUID();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,:jobType,:subject,'PENDING',:payload::jsonb,:idempotencyKey)")
            .param("id", jobId).param("owner", ownerId).param("jobType", JOB_TYPE).param("subject", exportId)
            .param("payload", payloadJson(jobId, exportId, canonical))
            .param("idempotencyKey", exportId.toString()).update();
    }

    /** ADR-0002 payload envelope v1 (job_type COMBINED_EXPORT already declared at
     * {@code 0002-frontera-spring-worker.md:52}). All values are server-generated. */
    private static String payloadJson(UUID jobId, UUID exportId, SnapshotV1Writer.CanonicalSnapshot canonical) {
        return "{\"contract\":\"scenery-foundry.geometry-job\",\"version\":1,\"jobType\":\"" + JOB_TYPE + "\","
            + "\"jobId\":\"" + jobId + "\",\"subjectId\":\"" + exportId + "\","
            + "\"input\":{\"storageKey\":\"exports/" + exportId + "/snapshot.json\",\"sha256\":\"" + canonical.sha256() + "\","
            + "\"canonicalizerContract\":\"" + canonical.contract() + "\"},"
            + "\"output\":{\"directory\":\"exports/" + exportId + "\"},"
            + "\"options\":{\"geometryPolicyVersion\":1,\"booleanEngine\":\"" + SnapshotV1Writer.BOOLEAN_ENGINE + "\"}}";
    }

    private record CaptureObject(long sceneObjectId, UUID assetId, String storageKey, String sha256,
                                 int matrixContractVersion, double[] matrix, String processingStatus, String geometryStatus) { }
}
