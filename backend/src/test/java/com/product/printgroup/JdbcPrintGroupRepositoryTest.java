package com.product.printgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.product.scene.OwnedResourceNotFoundException;

/** Covers both the repository (task 3.1) and the service's ownership resolution (tasks 3.2/3.6) against a
 * real Postgres container — one Testcontainers instance instead of a parallel in-memory simulation. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcPrintGroupRepositoryTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired JdbcPrintGroupRepository repository;
    @Autowired PrintGroupService service;

    @Test
    void createsListsFindsAndDeletesThroughTheServiceAndTheRepositoryWhileHidingEveryActionFromAForeignOwner() {
        var owner = insertUser();
        var foreign = insertUser();
        var project = insertProject(owner);

        var created = service.create(owner, project, "Batch 1");

        assertThat(service.list(owner, project)).containsExactly(created);
        assertThat(service.find(owner, created.id())).isEqualTo(created);
        assertThat(repository.findByProject(project)).containsExactly(created);
        assertThat(repository.projectExistsForOwner(owner, project)).isTrue();
        assertThat(repository.projectExistsForOwner(foreign, project)).isFalse();
        assertThatThrownBy(() -> service.create(foreign, project, "Batch 2")).isInstanceOf(OwnedResourceNotFoundException.class);
        assertThatThrownBy(() -> service.list(foreign, project)).isInstanceOf(OwnedResourceNotFoundException.class);
        assertThatThrownBy(() -> service.find(foreign, created.id())).isInstanceOf(OwnedResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(foreign, created.id())).isInstanceOf(OwnedResourceNotFoundException.class);

        service.delete(owner, created.id());

        assertThat(service.list(owner, project)).isEmpty();
    }

    @Test
    void rejectsABlankOrWhitespaceOnlyNameBeforeItEverReachesTheDatabase() {
        var owner = insertUser();
        var project = insertProject(owner);

        assertThatThrownBy(() -> service.create(owner, project, "   ")).isInstanceOf(InvalidPrintGroupException.class);
        assertThatThrownBy(() -> service.create(owner, project, "")).isInstanceOf(InvalidPrintGroupException.class);
        assertThat(service.list(owner, project)).isEmpty();
    }

    @Test
    void deletingAGroupClearsMembershipButKeepsTheSceneObjectsInTheScene() {
        var owner = insertUser();
        var project = insertProject(owner);
        var asset = insertAsset(project, owner);
        var group = new PrintGroup(UUID.randomUUID(), project, owner, "Batch 1");
        repository.save(group);
        insertSceneObject(project, owner, asset, group.id());

        repository.deleteByOwnerAndId(owner, group.id());

        assertThat(repository.findByOwnerAndId(owner, group.id())).isEmpty();
        assertThat(jdbc.sql("select print_group_id from scene_objects where project_id=:project and id=1")
            .param("project", project).query(UUID.class).optional()).isEmpty();
        assertThat(jdbc.sql("select count(*) from scene_objects where project_id=:project").param("project", project)
            .query(Long.class).single()).isEqualTo(1L);
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private UUID insertProject(UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", id).param("owner", owner).update();
        return id;
    }

    private UUID insertAsset(UUID project, UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,'READY','VALID_VOLUME','assets/a.stl','" + "a".repeat(64) + "')")
            .param("id", id).param("owner", owner).update();
        return id;
    }

    private void insertSceneObject(UUID project, UUID owner, UUID asset, UUID printGroupId) {
        jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,"
                + "quaternion_xyzw,scale,matrix_world_column_major,print_group_id) values "
                + "(1,:project,:owner,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}',:group)")
            .param("project", project).param("owner", owner).param("asset", asset).param("group", printGroupId).update();
    }
}
