package com.product.level;

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
public class JdbcLevelRepository implements LevelRepository {
    private final JdbcClient jdbc;
    public JdbcLevelRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public void save(Level level) {
        jdbc.sql("insert into levels(id,project_id,owner_id,name) values (:id,:project,:owner,:name)")
            .param("id", level.id()).param("project", level.projectId()).param("owner", level.ownerId()).param("name", level.name()).update();
    }

    @Override public List<Level> findByProject(UUID projectId) {
        return jdbc.sql("select id,project_id,owner_id,name from levels where project_id=:project order by created_at, id")
            .param("project", projectId).query(this::map).list();
    }

    @Override public Optional<Level> findByOwnerAndId(UUID ownerId, UUID id) {
        return jdbc.sql("select id,project_id,owner_id,name from levels where id=:id and owner_id=:owner")
            .param("id", id).param("owner", ownerId).query(this::map).optional();
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteByOwnerAndId(UUID ownerId, UUID id) {
        jdbc.sql("update scene_objects set level_id=null where level_id=:id").param("id", id).update();
        jdbc.sql("delete from levels where id=:id and owner_id=:owner").param("id", id).param("owner", ownerId).update();
    }

    @Override public boolean projectExistsForOwner(UUID ownerId, UUID projectId) {
        return jdbc.sql("select id from projects where id=:project and owner_id=:owner")
            .param("project", projectId).param("owner", ownerId).query(UUID.class).optional().isPresent();
    }

    private Level map(ResultSet row, int index) throws SQLException {
        return new Level(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
            row.getObject("owner_id", UUID.class), row.getString("name"));
    }
}
