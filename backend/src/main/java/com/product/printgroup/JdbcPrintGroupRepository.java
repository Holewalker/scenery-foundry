package com.product.printgroup;

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
public class JdbcPrintGroupRepository implements PrintGroupRepository {
    private final JdbcClient jdbc;
    public JdbcPrintGroupRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public void save(PrintGroup group) {
        jdbc.sql("insert into print_groups(id,project_id,owner_id,name) values (:id,:project,:owner,:name)")
            .param("id", group.id()).param("project", group.projectId()).param("owner", group.ownerId()).param("name", group.name()).update();
    }

    @Override public List<PrintGroup> findByProject(UUID projectId) {
        return jdbc.sql("select id,project_id,owner_id,name from print_groups where project_id=:project order by created_at, id")
            .param("project", projectId).query(this::map).list();
    }

    @Override public Optional<PrintGroup> findByOwnerAndId(UUID ownerId, UUID id) {
        return jdbc.sql("select id,project_id,owner_id,name from print_groups where id=:id and owner_id=:owner")
            .param("id", id).param("owner", ownerId).query(this::map).optional();
    }

    /** No ON DELETE SET NULL on the composite FK, so membership clears explicitly before the delete, same tx. */
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteByOwnerAndId(UUID ownerId, UUID id) {
        jdbc.sql("update scene_objects set print_group_id=null where print_group_id=:id").param("id", id).update();
        jdbc.sql("delete from print_groups where id=:id and owner_id=:owner").param("id", id).param("owner", ownerId).update();
    }

    @Override public boolean projectExistsForOwner(UUID ownerId, UUID projectId) {
        return jdbc.sql("select id from projects where id=:project and owner_id=:owner")
            .param("project", projectId).param("owner", ownerId).query(UUID.class).optional().isPresent();
    }

    private PrintGroup map(ResultSet row, int index) throws SQLException {
        return new PrintGroup(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
            row.getObject("owner_id", UUID.class), row.getString("name"));
    }
}
