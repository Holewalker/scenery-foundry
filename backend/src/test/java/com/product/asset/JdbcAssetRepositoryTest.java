package com.product.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.product.scene.AssetProcessingStatus;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcAssetRepositoryTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired JdbcAssetRepository repository;

    @Test
    void returnsEveryProcessingAndGeometryStateForTheOwnerWithoutError() {
        UUID owner = insertUser();
        insertAsset(owner, "UPLOADED", "UNKNOWN");
        insertAsset(owner, "PROCESSING", "UNKNOWN");
        insertAsset(owner, "READY", "VALID_VOLUME");
        insertAsset(owner, "READY", "INVALID_VOLUME");
        insertAsset(owner, "FAILED", "UNKNOWN");

        assertThat(repository.findCatalogForOwner(owner)).extracting(AssetCatalogEntry::processingStatus)
            .containsExactlyInAnyOrder(AssetProcessingStatus.UPLOADED, AssetProcessingStatus.PROCESSING,
                AssetProcessingStatus.READY, AssetProcessingStatus.READY, AssetProcessingStatus.FAILED);
    }

    @Test
    void hidesOtherOwnersAssetsFromTheCatalog() {
        UUID owner = insertUser();
        UUID stranger = insertUser();
        insertAsset(owner, "READY", "VALID_VOLUME");
        insertAsset(stranger, "READY", "VALID_VOLUME");

        assertThat(repository.findCatalogForOwner(owner)).hasSize(1);
    }

    @Test
    void findByOwnerAndIdReturnsTheEntryOnlyForItsOwningOwner() {
        UUID owner = insertUser();
        UUID stranger = insertUser();
        UUID assetId = insertAsset(owner, "READY", "VALID_VOLUME");

        assertThat(repository.findByOwnerAndId(owner, assetId)).isPresent()
            .get().extracting(AssetCatalogEntry::id).isEqualTo(assetId);
        assertThat(repository.findByOwnerAndId(stranger, assetId)).isEmpty();
    }

    @Test
    void findByOwnerAndIdReturnsEmptyForAMissingAsset() {
        UUID owner = insertUser();

        assertThat(repository.findByOwnerAndId(owner, UUID.randomUUID())).isEmpty();
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private UUID insertAsset(UUID owner, String processing, String geometry) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,:processing,:geometry,'assets/a.stl','" + "a".repeat(64) + "')")
            .param("id", id).param("owner", owner).param("processing", processing).param("geometry", geometry).update();
        return id;
    }
}
