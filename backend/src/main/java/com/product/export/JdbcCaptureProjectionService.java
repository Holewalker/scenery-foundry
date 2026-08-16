package com.product.export;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcCaptureProjectionService {
    private final JdbcClient jdbc;
    private final JdbcExportRepository exports;
    private final SnapshotV1Writer writer = new SnapshotV1Writer();

    public JdbcCaptureProjectionService(JdbcClient jdbc, JdbcExportRepository exports) {
        this.jdbc = jdbc;
        this.exports = exports;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID capture(UUID ownerId, UUID projectId) {
        lockProject(ownerId, projectId);
        List<CaptureObject> projection = jdbc.sql("select o.id,o.asset_id,a.storage_key,a.original_sha256,o.matrix_contract_version,o.matrix_world_column_major,a.processing_status,a.geometry_status from scene_objects o join assets a on a.id=o.asset_id and a.owner_id=o.owner_id where o.project_id=:project order by o.id asc")
            .param("project", projectId).query((row, index) -> map(row.getLong("id"), row.getObject("asset_id", UUID.class),
                row.getString("storage_key"), row.getString("original_sha256"), row.getInt("matrix_contract_version"), row.getArray("matrix_world_column_major"),
                row.getString("processing_status"), row.getString("geometry_status"))).list();
        if (projection.isEmpty() || projection.size() > 250) throw new InvalidCaptureException("scene cardinality must be 1..250");
        List<SnapshotV1.ObjectSnapshot> objects = projection.stream().map(this::validate).toList();
        UUID exportId = UUID.randomUUID();
        SnapshotV1 snapshot = new SnapshotV1(exportId, projectId, ownerId, Instant.now(), objects);
        exports.capture(ownerId, snapshot, writer.canonicalize(snapshot));
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

    private record CaptureObject(long sceneObjectId, UUID assetId, String storageKey, String sha256,
                                 int matrixContractVersion, double[] matrix, String processingStatus, String geometryStatus) { }
}
