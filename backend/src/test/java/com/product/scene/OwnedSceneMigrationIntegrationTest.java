package com.product.scene;

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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OwnedSceneMigrationIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;

    @Test
    void appliesCompositeAssetReferenceAndSafeSceneObjectIdConstraint() {
        var owner = UUID.randomUUID();
        var project = UUID.randomUUID();
        var asset = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,:hash)")
            .param("id", owner).param("email", "owner-" + owner + "@example.com").param("hash", "hash").update();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", project).param("owner", owner).update();
        jdbc.sql("insert into prepared_assets(id,project_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:project,'READY','VALID_VOLUME','asset','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')")
            .param("id", asset).param("project", project).update();

        assertThatThrownBy(() -> jdbc.sql("insert into scene_objects(id,project_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major) values (0,:project,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}')")
            .param("project", project).param("asset", asset).update()).isInstanceOf(Exception.class);

        assertThat(jdbc.sql("select count(*) from prepared_assets where project_id=:project and id=:asset")
            .param("project", project).param("asset", asset).query(Long.class).single()).isEqualTo(1L);
    }
}
