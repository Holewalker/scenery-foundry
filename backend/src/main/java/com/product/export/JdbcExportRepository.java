package com.product.export;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcExportRepository {
    private final JdbcClient jdbc;

    public JdbcExportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void capture(UUID ownerId, SnapshotV1 snapshot, SnapshotV1Writer.CanonicalSnapshot canonical) {
        if (!ownerId.equals(snapshot.ownerId())) throw new ExportNotFoundException();
        lockProject(ownerId, snapshot.projectId());
        jdbc.sql("insert into combined_exports(id,project_id,owner_id,captured_at,snapshot_version,boolean_engine,boolean_engine_version,geometry_policy_version,requested_output_format) values (:id,:project,:owner,:captured,1,:engine,:engineVersion,:policy,:output)")
            .param("id", snapshot.exportId()).param("project", snapshot.projectId()).param("owner", ownerId).param("captured", OffsetDateTime.ofInstant(snapshot.capturedAt(), ZoneOffset.UTC))
            .param("engine", SnapshotV1Writer.BOOLEAN_ENGINE).param("engineVersion", SnapshotV1Writer.BOOLEAN_ENGINE_VERSION)
            .param("policy", SnapshotV1Writer.GEOMETRY_POLICY_VERSION).param("output", SnapshotV1Writer.REQUESTED_OUTPUT_FORMAT).update();
        jdbc.sql("insert into export_snapshots(export_id,canonical_bytes,snapshot_sha256,canonicalizer_contract) values (:id,:bytes,:sha,:contract)")
            .param("id", snapshot.exportId()).param("bytes", canonical.canonicalBytes()).param("sha", canonical.sha256()).param("contract", canonical.contract()).update();
    }

    public Optional<ExportSnapshot> findSnapshot(UUID ownerId, UUID exportId) {
        return jdbc.sql("select s.canonical_bytes,s.snapshot_sha256,s.canonicalizer_contract from export_snapshots s join combined_exports e on e.id=s.export_id join projects p on p.id=e.project_id and p.owner_id=e.owner_id where e.id=:id and p.owner_id=:owner")
            .param("id", exportId).param("owner", ownerId)
            .query((row, index) -> new ExportSnapshot(row.getBytes("canonical_bytes"), row.getString("snapshot_sha256"), row.getString("canonicalizer_contract"))).optional();
    }

    private void lockProject(UUID ownerId, UUID projectId) {
        boolean found = jdbc.sql("select id from projects where id=:project and owner_id=:owner for update")
            .param("project", projectId).param("owner", ownerId).query(UUID.class).optional().isPresent();
        if (!found) throw new ExportNotFoundException();
    }
}

