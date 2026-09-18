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
        jdbc.sql("insert into projects(id, owner_id, name) values (:id, :owner, :name)")
            .param("id", project.id()).param("owner", project.ownerId()).param("name", project.name()).update();
    }
    @Override public Optional<Project> findProjectByOwner(UUID ownerId, UUID projectId) {
        return jdbc.sql("select id, owner_id, name from projects where id = :id and owner_id = :owner")
            .param("id", projectId).param("owner", ownerId)
            .query((resultSet, row) -> mapProject(resultSet))
            .optional();
    }
    @Override public List<Project> findProjectsByOwner(UUID ownerId) {
        return jdbc.sql("select id, owner_id, name from projects where owner_id = :owner order by id")
            .param("owner", ownerId)
            .query((resultSet, row) -> mapProject(resultSet))
            .list();
    }
    private static Project mapProject(ResultSet resultSet) throws SQLException {
        return new Project(resultSet.getObject("id", UUID.class), resultSet.getObject("owner_id", UUID.class), resultSet.getString("name"));
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
        return jdbc.sql("select id,asset_id,quaternion_xyzw,scale,matrix_world_column_major,print_group_id,level_id from scene_objects where project_id=:project order by id")
            .param("project", projectId).query((row, index) -> mapSceneObject(projectId, row)).list();
    }
    /** Must persist print_group_id/level_id here (D6): this delete-then-reinsert is the SINGLE writer of
     * scene_objects, so a side-endpoint would be silently wiped on the next scene save.
     *
     * <p>The version {@code UPDATE} runs first, before the delete/insert (ADR-0007 D2): it takes the
     * {@code projects} row lock, serializing concurrent writers of the same project, and under
     * {@code READ COMMITTED} PostgreSQL re-evaluates the predicate against the just-committed version once
     * the lock is released — so a losing concurrent writer sees zero rows and never touches
     * {@code scene_objects}, rather than a {@code SELECT}-then-update race. */
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Long> replaceScene(UUID projectId, long expectedVersion, List<SceneObject> objects) {
        var newVersion = jdbc.sql("update projects set scene_version = scene_version + 1 "
                + "where id=:project and scene_version=:expected returning scene_version")
            .param("project", projectId).param("expected", expectedVersion).query(Long.class).optional();
        if (newVersion.isEmpty()) return Optional.empty();

        writeSceneObjects(projectId, objects);
        return newVersion;
    }

    /** Codex PR #52 finding 2: the transitional unchecked write for a client that omitted {@code version}
     * (ADR-0007). Unlike {@link #replaceScene}, the {@code UPDATE} carries no {@code scene_version}
     * predicate, so it always affects exactly one row regardless of the value any caller last read — true
     * last-writer-wins. The row lock it takes still serializes concurrent unchecked writers of the same
     * project (D2), so both succeed in lock-acquisition order rather than one spuriously losing a
     * compare-and-swap against a version read moments earlier in a separate step. */
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public long replaceSceneUnchecked(UUID projectId, List<SceneObject> objects) {
        long newVersion = jdbc.sql("update projects set scene_version = scene_version + 1 "
                + "where id=:project returning scene_version")
            .param("project", projectId).query(Long.class).single();

        writeSceneObjects(projectId, objects);
        return newVersion;
    }

    /** Must persist print_group_id/level_id here (D6): this delete-then-reinsert is the SINGLE writer of
     * scene_objects, so a side-endpoint would be silently wiped on the next scene save. Shared by both the
     * checked ({@link #replaceScene}) and unchecked ({@link #replaceSceneUnchecked}) writes: both already
     * hold the {@code projects} row lock from their version {@code UPDATE} by the time this runs. */
    private void writeSceneObjects(UUID projectId, List<SceneObject> objects) {
        jdbc.sql("delete from scene_objects where project_id=:project").param("project", projectId).update();
        for (SceneObject object : objects) {
            var transform = object.transform();
            double[] matrix = transform.matrixWorldColumnMajor();
            jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major,print_group_id,level_id) "
                    + "values (:id,:project,(select owner_id from projects where id=:project),:asset,1,:translation::double precision[],:quaternion::double precision[],:scale::double precision[],:matrix::double precision[],:group,:level)")
                .param("id", object.id().value()).param("project", projectId).param("asset", object.assetId())
                .param("translation", arrayLiteral(new double[] {matrix[12], matrix[13], matrix[14]}))
                .param("quaternion", arrayLiteral(transform.quaternionXyzw())).param("scale", arrayLiteral(transform.scale()))
                .param("matrix", arrayLiteral(matrix)).param("group", object.printGroupId()).param("level", object.levelId()).update();
        }
    }

    @Override
    public long findSceneVersion(UUID projectId) {
        return jdbc.sql("select scene_version from projects where id=:project").param("project", projectId).query(Long.class).single();
    }

    /** Codex PR #52 finding 1: {@code scene_version} and {@code scene_objects} in ONE statement (a
     * {@code LEFT JOIN} so a scene with zero objects still returns the project's row), so a single
     * consistent read snapshot backs both — structurally impossible for a concurrent commit to land between
     * "read the version" and "read the objects" the way it could with two independent queries. */
    @Override
    public SceneSnapshot findScene(UUID projectId) {
        record Row(long version, SceneObject object) {}
        var rows = jdbc.sql("select p.scene_version as version, o.id, o.asset_id, o.quaternion_xyzw, o.scale, "
                + "o.matrix_world_column_major, o.print_group_id, o.level_id "
                + "from projects p left join scene_objects o on o.project_id = p.id "
                + "where p.id = :project order by o.id")
            .param("project", projectId)
            .query((row, index) -> new Row(row.getLong("version"), row.getObject("id") == null ? null : mapSceneObject(projectId, row)))
            .list();
        if (rows.isEmpty()) throw new OwnedResourceNotFoundException();
        var objects = rows.stream().map(Row::object).filter(java.util.Objects::nonNull).toList();
        return new SceneSnapshot(rows.get(0).version(), objects);
    }

    private PreparedAsset mapAsset(UUID projectId, ResultSet row) throws SQLException {
        return new PreparedAsset(row.getObject("id", UUID.class), projectId,
            AssetProcessingStatus.valueOf(row.getString("processing_status")), AssetGeometryStatus.valueOf(row.getString("geometry_status")),
            row.getString("storage_key"), row.getString("original_sha256"));
    }

    private static SceneObject mapSceneObject(UUID projectId, ResultSet row) throws SQLException {
        var transform = SceneTransform.of(toDoubleArray(row.getArray("matrix_world_column_major")),
            toDoubleArray(row.getArray("quaternion_xyzw")), toDoubleArray(row.getArray("scale")));
        return new SceneObject(SceneObjectId.of(row.getLong("id")), projectId, row.getObject("asset_id", UUID.class), transform,
            row.getObject("print_group_id", UUID.class), row.getObject("level_id", UUID.class));
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
