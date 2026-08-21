package com.product.piecesexport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.product.printgroup.PrintGroupService;
import com.product.storage.StorageResolver;

/**
 * Synchronous, no {@code geometry_jobs} row (proposal D5): resolves ownership, projects the group's scene
 * objects, dedups by {@code asset_id}, and enforces both caps before any ZIP byte is written. Eligibility here
 * is {@code processing_status = READY} only — unlike Combined Export, Pieces Export ships already-stored
 * originals with no boolean union, so ADR-0006's {@code INVALID_VOLUME} exclusion (scoped explicitly to
 * Combined Export) does not extend to it; {@code geometry_status} is carried into the manifest, not gated on.
 */
@Service
public class PiecesExportService {
    private static final int MAX_SCENE_OBJECTS = 250;

    private final JdbcClient jdbc;
    private final PrintGroupService printGroupService;
    private final StorageResolver storageResolver;
    private final PiecesManifestWriter manifestWriter = new PiecesManifestWriter();
    private final PiecesExportProperties properties;

    public PiecesExportService(JdbcClient jdbc, PrintGroupService printGroupService, StorageResolver storageResolver,
            PiecesExportProperties properties) {
        this.jdbc = jdbc;
        this.printGroupService = printGroupService;
        this.storageResolver = storageResolver;
        this.properties = properties;
    }

    public PiecesExportPlan prepare(UUID ownerId, UUID printGroupId) {
        var group = printGroupService.find(ownerId, printGroupId); // 404 (OwnedResourceNotFoundException) for foreign/nonexistent

        List<MemberRow> members = jdbc.sql("""
                SELECT o.id AS scene_object_id, o.asset_id, a.storage_key, a.original_sha256, a.geometry_status, a.processing_status
                FROM scene_objects o JOIN assets a ON a.id = o.asset_id AND a.owner_id = o.owner_id
                WHERE o.print_group_id = :group AND o.owner_id = :owner ORDER BY o.id ASC
                """)
            .param("group", printGroupId).param("owner", ownerId)
            .query(this::mapMember).list();

        if (members.isEmpty()) {
            throw new PiecesExportValidationException("print group " + printGroupId + " has no member scene objects");
        }
        if (members.size() > MAX_SCENE_OBJECTS) {
            throw new PiecesExportValidationException("print group " + printGroupId + " exceeds the " + MAX_SCENE_OBJECTS + " scene-object ceiling");
        }
        for (MemberRow member : members) {
            if (!"READY".equals(member.processingStatus())) {
                throw new PiecesExportValidationException("scene object " + member.sceneObjectId() + " (asset " + member.assetId()
                    + ") is not ready for export: processing_status=" + member.processingStatus());
            }
        }

        Map<UUID, MemberRow> distinctAssets = new LinkedHashMap<>();
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (MemberRow member : members) {
            distinctAssets.putIfAbsent(member.assetId(), member);
            quantities.merge(member.assetId(), 1, Integer::sum);
        }

        long totalBytes = 0L;
        for (MemberRow asset : distinctAssets.values()) {
            try {
                totalBytes = Math.addExact(totalBytes, storageResolver.size(asset.storageKey()));
            } catch (ArithmeticException overflow) {
                throw new PiecesExportTooLargeException("print group " + printGroupId
                    + " pieces exceed the " + properties.maxUncompressedBytes() + "-byte cap");
            }
        }
        if (totalBytes > properties.maxUncompressedBytes()) {
            throw new PiecesExportTooLargeException("print group " + printGroupId + " pieces total " + totalBytes
                + " uncompressed bytes, exceeding the " + properties.maxUncompressedBytes() + "-byte cap");
        }

        List<PiecesManifest.Piece> manifestPieces = new ArrayList<>();
        List<PiecesExportPlan.PieceFile> pieceFiles = new ArrayList<>();
        for (MemberRow asset : distinctAssets.values()) {
            String fileName = "pieces/" + asset.assetId() + ".stl";
            manifestPieces.add(new PiecesManifest.Piece(asset.assetId(), fileName, asset.originalSha256(),
                asset.geometryStatus(), quantities.get(asset.assetId())));
            pieceFiles.add(new PiecesExportPlan.PieceFile(fileName, asset.storageKey()));
        }

        var manifest = new PiecesManifest(printGroupId, group.projectId(), Instant.now(), manifestPieces);
        return new PiecesExportPlan(manifestWriter.write(manifest), pieceFiles);
    }

    private MemberRow mapMember(ResultSet row, int index) throws SQLException {
        return new MemberRow(row.getLong("scene_object_id"), row.getObject("asset_id", UUID.class),
            row.getString("storage_key"), row.getString("original_sha256"),
            row.getString("geometry_status"), row.getString("processing_status"));
    }

    private record MemberRow(long sceneObjectId, UUID assetId, String storageKey, String originalSha256,
                              String geometryStatus, String processingStatus) { }
}
