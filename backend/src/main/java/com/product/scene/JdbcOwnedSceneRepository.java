package com.product.scene;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
}
