package com.product.piecesexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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

import com.product.scene.OwnedResourceNotFoundException;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PiecesExportServiceTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    private static final AtomicLong SCENE_OBJECT_ID = new AtomicLong(1);
    static Path storageRoot;

    @Autowired JdbcClient jdbc;
    @Autowired PiecesExportService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storageRoot = Files.createTempDirectory("pieces-export-test");
        registry.add("SCENE_DATA_ROOT", storageRoot::toString);
        registry.add("app.piecesexport.max-uncompressed-bytes", () -> "100");
    }

    @Test
    void dedupesByAssetIdAndCarriesQuantityAndGeometryStatusIncludingInvalidVolumeAndUnknown() {
        var fixture = fixture();
        var validAsset = insertAsset(fixture.owner(), "READY", "VALID_VOLUME", "cube-bytes");
        var invalidAsset = insertAsset(fixture.owner(), "READY", "INVALID_VOLUME", "shard-bytes");
        var unknownAsset = insertAsset(fixture.owner(), "READY", "UNKNOWN", "blob-bytes");
        insertSceneObject(fixture, validAsset);
        insertSceneObject(fixture, validAsset);
        insertSceneObject(fixture, invalidAsset);
        insertSceneObject(fixture, unknownAsset);

        var plan = service.prepare(fixture.owner(), fixture.group());

        assertThat(plan.pieces()).hasSize(3);
        assertThat(plan.pieces()).extracting(PiecesExportPlan.PieceFile::fileName)
            .containsExactlyInAnyOrder("pieces/" + validAsset + ".stl", "pieces/" + invalidAsset + ".stl", "pieces/" + unknownAsset + ".stl");
        String manifestJson = new String(plan.manifestJson(), StandardCharsets.UTF_8);
        assertThat(manifestJson).contains("\"assetId\":\"" + validAsset + "\"").contains("\"quantity\":2").contains("\"geometryStatus\":\"VALID_VOLUME\"");
        assertThat(manifestJson).contains("\"assetId\":\"" + invalidAsset + "\"").contains("\"geometryStatus\":\"INVALID_VOLUME\"");
        assertThat(manifestJson).contains("\"assetId\":\"" + unknownAsset + "\"").contains("\"geometryStatus\":\"UNKNOWN\"");
    }

    @Test
    void rejectsTheWholeRequestNamingTheOffendingObjectWhenAnyMemberIsNotReady() {
        var fixture = fixture();
        var ready = insertAsset(fixture.owner(), "READY", "VALID_VOLUME", "cube-bytes");
        var notReady = insertAsset(fixture.owner(), "PROCESSING", "UNKNOWN", "pending-bytes");
        insertSceneObject(fixture, ready);
        long offendingId = insertSceneObject(fixture, notReady);

        assertThatThrownBy(() -> service.prepare(fixture.owner(), fixture.group()))
            .isInstanceOf(PiecesExportValidationException.class)
            .hasMessageContaining(String.valueOf(offendingId));
    }

    @Test
    void rejectsAnEmptyPrintGroup() {
        var fixture = fixture();

        assertThatThrownBy(() -> service.prepare(fixture.owner(), fixture.group()))
            .isInstanceOf(PiecesExportValidationException.class);
    }

    @Test
    void rejectsGroupsWithMoreThanTwoHundredFiftySceneObjectsBeforeAnyDedupOrCapCheck() {
        var fixture = fixture();
        var asset = insertAsset(fixture.owner(), "READY", "VALID_VOLUME", "cube-bytes");
        for (int i = 0; i < 251; i++) insertSceneObject(fixture, asset);

        assertThatThrownBy(() -> service.prepare(fixture.owner(), fixture.group()))
            .isInstanceOf(PiecesExportValidationException.class);
    }

    @Test
    void rejectsWhenDistinctAssetUncompressedBytesExceedTheConfiguredCap() {
        var fixture = fixture();
        var asset = insertAsset(fixture.owner(), "READY", "VALID_VOLUME", "x".repeat(200));
        insertSceneObject(fixture, asset);

        assertThatThrownBy(() -> service.prepare(fixture.owner(), fixture.group()))
            .isInstanceOf(PiecesExportTooLargeException.class);
    }

    @Test
    void hidesAForeignOrNonexistentPrintGroupAsNotFound() {
        var fixture = fixture();
        var foreign = insertUser();

        assertThatThrownBy(() -> service.prepare(foreign, fixture.group())).isInstanceOf(OwnedResourceNotFoundException.class);
        assertThatThrownBy(() -> service.prepare(fixture.owner(), UUID.randomUUID())).isInstanceOf(OwnedResourceNotFoundException.class);
    }

    private Fixture fixture() {
        var owner = insertUser();
        var project = insertProject(owner);
        var group = UUID.randomUUID();
        jdbc.sql("insert into print_groups(id,project_id,owner_id,name) values (:id,:project,:owner,'Batch 1')")
            .param("id", group).param("project", project).param("owner", owner).update();
        return new Fixture(owner, project, group);
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

    private UUID insertAsset(UUID owner, String processingStatus, String geometryStatus, String contents) {
        var id = UUID.randomUUID();
        var key = "assets/" + id + "/original.stl";
        writeStoredFile(key, contents);
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,:processing,:geometry,:key,'" + "a".repeat(64) + "')")
            .param("id", id).param("owner", owner).param("processing", processingStatus)
            .param("geometry", geometryStatus).param("key", key).update();
        return id;
    }

    private long insertSceneObject(Fixture fixture, UUID asset) {
        long id = SCENE_OBJECT_ID.getAndIncrement();
        jdbc.sql("insert into scene_objects(id,project_id,owner_id,asset_id,matrix_contract_version,translation_mm,"
                + "quaternion_xyzw,scale,matrix_world_column_major,print_group_id) values "
                + "(:id,:project,:owner,:asset,1,'{0,0,0}','{0,0,0,1}','{1,1,1}','{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}',:group)")
            .param("id", id).param("project", fixture.project()).param("owner", fixture.owner())
            .param("asset", asset).param("group", fixture.group()).update();
        return id;
    }

    private void writeStoredFile(String key, String contents) {
        try {
            var path = storageRoot.resolve(key);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(UUID owner, UUID project, UUID group) { }
}
