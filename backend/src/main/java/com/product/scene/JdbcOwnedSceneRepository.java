package com.product.scene;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOwnedSceneRepository implements OwnedSceneRepository {
    private final JdbcClient jdbc;
    public JdbcOwnedSceneRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public void save(Project project) {
        jdbc.sql("insert into projects(id, owner_id) values (:id, :owner)")
            .param("id", project.id()).param("owner", project.ownerId()).update();
    }
    @Override public Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId) {
        return jdbc.sql("select id, owner_id from projects where id = :id and owner_id = :owner")
            .param("id", projectId).param("owner", ownerId)
            .query((resultSet, row) -> new Project(resultSet.getObject("id", UUID.class), resultSet.getObject("owner_id", UUID.class)))
            .optional();
    }
    /* Assets are owner-scoped since V5; "a project's assets" is now derived via scene_objects. These two
     * legacy browsing methods retire with ProjectController's routes in PR3 (task 3.9). */
    @Override public List<PreparedAsset> findAssets(UUID projectId) {
        return jdbc.sql("select distinct a.id,a.processing_status,a.geometry_status,a.storage_key,a.original_sha256 "
                + "from assets a join scene_objects o on o.asset_id=a.id and o.project_id=:project order by a.id")
            .param("project", projectId).query((row, index) -> mapAsset(projectId, row)).list();
    }
    @Override public Optional<PreparedAsset> findAsset(UUID projectId, UUID assetId) {
        return jdbc.sql("select a.id,a.processing_status,a.geometry_status,a.storage_key,a.original_sha256 "
                + "from assets a join scene_objects o on o.asset_id=a.id and o.project_id=:project where a.id=:asset limit 1")
            .param("project", projectId).param("asset", assetId).query((row, index) -> mapAsset(projectId, row)).optional();
    }
    @Override public Set<UUID> findReadyAssetIds(UUID ownerId) {
        return new HashSet<>(jdbc.sql("select id from assets where owner_id=:owner and processing_status='READY'")
            .param("owner", ownerId).query(UUID.class).list());
    }
    @Override public List<SceneObject> findSceneObjects(UUID projectId) {
        return jdbc.sql("select id,asset_id,quaternion_xyzw,scale,matrix_world_column_major from scene_objects where project_id=:project order by id")
            .param("project", projectId).query((row, index) -> mapSceneObject(projectId, row)).list();
    }
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public void replaceScene(UUID projectId, List<SceneObject> objects) {
        jdbc.sql("delete from scene_objects where project_id=:project").param("project", projectId).update();
        for (SceneObject object : objects) {
            var transform = object.transform();
            double[] matrix = transform.matrixWorldColumnMajor();
            jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major) "
                    + "values (:id,:project,(select owner_id from projects where id=:project),:asset,1,:translation::double precision[],:quaternion::double precision[],:scale::double precision[],:matrix::double precision[])")
                .param("id", object.id().value()).param("project", projectId).param("asset", object.assetId())
                .param("translation", arrayLiteral(new double[] {matrix[12], matrix[13], matrix[14]}))
                .param("quaternion", arrayLiteral(transform.quaternionXyzw())).param("scale", arrayLiteral(transform.scale()))
                .param("matrix", arrayLiteral(matrix)).update();
        }
    }

    private PreparedAsset mapAsset(UUID projectId, ResultSet row) throws SQLException {
        return new PreparedAsset(row.getObject("id", UUID.class), projectId,
            AssetProcessingStatus.valueOf(row.getString("processing_status")), AssetGeometryStatus.valueOf(row.getString("geometry_status")),
            row.getString("storage_key"), row.getString("original_sha256"));
    }

    private static SceneObject mapSceneObject(UUID projectId, ResultSet row) throws SQLException {
        var transform = SceneTransform.of(toDoubleArray(row.getArray("matrix_world_column_major")),
            toDoubleArray(row.getArray("quaternion_xyzw")), toDoubleArray(row.getArray("scale")));
        return new SceneObject(SceneObjectId.of(row.getLong("id")), projectId, row.getObject("asset_id", UUID.class), transform);
    }

    private static double[] toDoubleArray(Array sqlArray) throws SQLException {
        Object[] boxed = (Object[]) sqlArray.getArray();
        double[] result = new double[boxed.length];
        for (int index = 0; index < boxed.length; index++) result[index] = ((Number) boxed[index]).doubleValue();
        return result;
    }

    private static String arrayLiteral(double[] values) {
        StringBuilder builder = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) builder.append(',');
            builder.append(values[index]);
        }
        return builder.append('}').toString();
    }
}
