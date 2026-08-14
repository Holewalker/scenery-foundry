package com.product.scene;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
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
    @Override public List<PreparedAsset> findAssets(UUID projectId) {
        return jdbc.sql("select id,project_id,processing_status,geometry_status,storage_key,original_sha256 from prepared_assets where project_id=:project order by id")
            .param("project", projectId).query(this::mapAsset).list();
    }
    @Override public Optional<PreparedAsset> findAsset(UUID projectId, UUID assetId) {
        return jdbc.sql("select id,project_id,processing_status,geometry_status,storage_key,original_sha256 from prepared_assets where project_id=:project and id=:asset")
            .param("project", projectId).param("asset", assetId).query(this::mapAsset).optional();
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
            jdbc.sql("insert into scene_objects(id,project_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major) "
                    + "values (:id,:project,:asset,1,:translation::double precision[],:quaternion::double precision[],:scale::double precision[],:matrix::double precision[])")
                .param("id", object.id().value()).param("project", projectId).param("asset", object.assetId())
                .param("translation", arrayLiteral(new double[] {matrix[12], matrix[13], matrix[14]}))
                .param("quaternion", arrayLiteral(transform.quaternionXyzw())).param("scale", arrayLiteral(transform.scale()))
                .param("matrix", arrayLiteral(matrix)).update();
        }
    }

    private PreparedAsset mapAsset(ResultSet row, int index) throws SQLException {
        return new PreparedAsset(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
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
