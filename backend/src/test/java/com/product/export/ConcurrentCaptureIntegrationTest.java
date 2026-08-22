package com.product.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentCaptureIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    static Path storageRoot;

    @Autowired JdbcClient jdbc;
    @Autowired JdbcCaptureProjectionService captures;
    @Autowired JdbcExportRepository exports;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storageRoot = Files.createTempDirectory("concurrent-capture-test");
        registry.add("SCENE_DATA_ROOT", storageRoot::toString);
    }

    @Test
    void serializesConcurrentCapturesAndCommitsTwoDistinctExports() throws Exception {
        Fixture fixture = fixture("asset");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return captures.capture(fixture.owner(), fixture.group()); });
            var second = pool.submit(() -> { start.await(); return captures.capture(fixture.owner(), fixture.group()); });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isNotEqualTo(second.get(30, TimeUnit.SECONDS));
        }
        assertThat(exportCount(fixture.project())).isEqualTo(2L);
    }

    @Test
    void waitingCaptureReadsTheMutationCommittedByTheProjectLockHolder() throws Exception {
        Fixture fixture = fixture("before");
        try (Connection connection = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            connection.prepareStatement("select id from projects where id='" + fixture.project() + "' for update").execute();
            connection.prepareStatement("update assets set storage_key='after' where owner_id='" + fixture.owner() + "'").executeUpdate();
            var capture = pool.submit(() -> captures.capture(fixture.owner(), fixture.group()));
            assertThat(capture.isDone()).isFalse();
            connection.commit();
            UUID exportId = capture.get(30, TimeUnit.SECONDS);
            assertThat(new String(exports.findSnapshot(fixture.owner(), exportId).orElseThrow().canonicalBytes(), StandardCharsets.UTF_8))
                .contains("after").doesNotContain("before");
        }
    }

    private Fixture fixture(String storageKey) {
        UUID owner = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID group = UUID.randomUUID(); UUID asset = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')").param("id", owner).param("email", owner + "@example.com").update();
        jdbc.sql("insert into projects(id,owner_id) values (:id,:owner)").param("id", project).param("owner", owner).update();
        jdbc.sql("insert into print_groups(id,project_id,owner_id,name) values (:id,:project,:owner,'group')").param("id", group).param("project", project).param("owner", owner).update();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) values (:id,:owner,'READY','VALID_VOLUME',:storage,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')").param("id", asset).param("owner", owner).param("storage", storageKey).update();
        jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,quaternion_xyzw,scale,matrix_world_column_major,print_group_id) values (1,:project,:owner,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}',:group)").param("project", project).param("owner", owner).param("asset", asset).param("group", group).update();
        return new Fixture(owner, project, group);
    }

    private long exportCount(UUID project) { return jdbc.sql("select count(*) from combined_exports where project_id=:project").param("project", project).query(Long.class).single(); }
    private record Fixture(UUID owner, UUID project, UUID group) { }
}
