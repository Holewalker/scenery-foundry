package com.product.asset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.product.scene.AssetGeometryStatus;
import com.product.scene.AssetProcessingStatus;

@Repository
public class JdbcAssetRepository {
    private static final String SELECT_COLUMNS = "id,owner_id,processing_status,geometry_status,storage_key,"
        + "original_sha256,preview_storage_key,triangle_count,error_code";

    private final JdbcClient jdbc;
    public JdbcAssetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<AssetCatalogEntry> findCatalogForOwner(UUID ownerId) {
        return jdbc.sql("select " + SELECT_COLUMNS + " from assets where owner_id=:owner order by created_at desc, id asc")
            .param("owner", ownerId).query(this::mapEntry).list();
    }

    /** Owner-scoped single lookup; a foreign owner or missing id both return empty (never disclose foreign data). */
    public Optional<AssetCatalogEntry> findByOwnerAndId(UUID ownerId, UUID assetId) {
        return jdbc.sql("select " + SELECT_COLUMNS + " from assets where owner_id=:owner and id=:id")
            .param("owner", ownerId).param("id", assetId).query(this::mapEntry).optional();
    }

    private AssetCatalogEntry mapEntry(ResultSet row, int index) throws SQLException {
        return new AssetCatalogEntry(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
            AssetProcessingStatus.valueOf(row.getString("processing_status")), AssetGeometryStatus.valueOf(row.getString("geometry_status")),
            row.getString("storage_key"), row.getString("original_sha256"), row.getString("preview_storage_key"),
            row.getObject("triangle_count", Long.class), row.getString("error_code"));
    }
}
