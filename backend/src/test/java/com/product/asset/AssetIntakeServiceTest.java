package com.product.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.product.scene.AssetProcessingStatus;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AssetIntakeServiceTest {
    private static final long MAX_FILE_SIZE_BYTES = 200L * 1024 * 1024;
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
    @Autowired JdbcClient jdbc;
    @Autowired AssetIntakeService service;
    static java.nio.file.Path storageRootPath;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) throws IOException {
        var root = Files.createTempDirectory("asset-intake-test");
        storageRootPath = root;
        registry.add("SCENE_DATA_ROOT", root::toString);
    }

    @Test
    void persistsOneAssetAndOnePendingJobInTheSameTransactionForAValidUpload() {
        var owner = insertUser();
        var file = new MockMultipartFile("file", "cube.stl", "application/octet-stream", "solid cube\nendsolid cube".getBytes());

        var result = service.intake(owner, file);

        assertThat(result.processingStatus()).isEqualTo(AssetProcessingStatus.UPLOADED);
        assertThat(jdbc.sql("select processing_status from assets where id=:id and owner_id=:owner")
            .param("id", result.assetId()).param("owner", owner).query(String.class).single()).isEqualTo("UPLOADED");
        assertThat(jdbc.sql("select status from geometry_jobs where id=:id and job_type='ASSET_PROCESSING'")
            .param("id", result.jobId()).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select subject_id from geometry_jobs where id=:id").param("id", result.jobId())
            .query(UUID.class).single()).isEqualTo(result.assetId());
    }

    @Test
    void rejectsOversizedOrNonStlUploadsBeforeTouchingStorageOrTheDatabase() {
        var owner = insertUser();
        MultipartFile huge = mock(MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getOriginalFilename()).thenReturn("cube.stl");
        when(huge.getSize()).thenReturn(201L * 1024 * 1024);
        var wrongType = new MockMultipartFile("file", "cube.exe", "application/octet-stream", "not an stl".getBytes());

        assertThatThrownBy(() -> service.intake(owner, huge)).isInstanceOf(AssetTooLargeException.class);
        assertThatThrownBy(() -> service.intake(owner, wrongType)).isInstanceOf(UnsupportedAssetMediaTypeException.class);
        assertThat(countRows("assets", owner)).isZero();
        assertThat(countRows("geometry_jobs", owner)).isZero();
    }

    @Test
    void returnsTheExistingJobForARepeatedUploadOfIdenticalBytesWithoutDuplicatingRows() {
        var owner = insertUser();
        var bytes = "solid cube\nendsolid cube".getBytes();

        var first = service.intake(owner, new MockMultipartFile("file", "cube.stl", "application/octet-stream", bytes));
        var second = service.intake(owner, new MockMultipartFile("file", "cube-again.stl", "application/octet-stream", bytes));

        assertThat(second.assetId()).isEqualTo(first.assetId());
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(countRows("assets", owner)).isEqualTo(1);
        assertThat(countRows("geometry_jobs", owner)).isEqualTo(1);
    }

    @Test
    void rejectsAnIdempotencyKeyCollisionAgainstAMismatchedStoredAsset() {
        var owner = insertUser();
        var bytes = "solid cube\nendsolid cube".getBytes();
        var sha256 = sha256Hex(bytes);
        var tamperedAssetId = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,'UPLOADED','UNKNOWN','assets/tampered.stl',:sha)")
            .param("id", tamperedAssetId).param("owner", owner).param("sha", "f".repeat(64)).update();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,'ASSET_PROCESSING',:subject,'PENDING','{}'::jsonb,:key)")
            .param("id", UUID.randomUUID()).param("owner", owner).param("subject", tamperedAssetId).param("key", sha256).update();

        assertThatThrownBy(() -> service.intake(owner, new MockMultipartFile("file", "cube.stl", "application/octet-stream", bytes)))
            .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @Timeout(15)
    void rejectsAnUploadThatCrossesTheCapMidStreamWithoutBufferingTheFullContentAndDeletesTheTemp() throws IOException {
        var owner = insertUser();
        var infinite = new RepeatingInputStream((byte) 'x');
        MultipartFile huge = mock(MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getOriginalFilename()).thenReturn("cube.stl");
        when(huge.getSize()).thenReturn(1L); // declared size lies small so the cheap pre-check does not short-circuit
        when(huge.getInputStream()).thenReturn(infinite);

        assertThatThrownBy(() -> service.intake(owner, huge)).isInstanceOf(AssetTooLargeException.class);

        // The bounded copy must abort within roughly one buffer's width of the cap, not after reading everything.
        assertThat(infinite.bytesRead()).isLessThan(MAX_FILE_SIZE_BYTES + (1024 * 1024));
        assertThat(countRows("assets", owner)).isZero();
        assertThat(countRows("geometry_jobs", owner)).isZero();
        assertThat(leftoverTempFiles()).isEmpty();
    }

    @Test
    void persistsTheRealStreamedByteCountAsSizeBytesRatherThanTheDeclaredMultipartSize() {
        var owner = insertUser();
        var bytes = "solid cube\nendsolid cube".getBytes();
        MultipartFile lying = mock(MultipartFile.class);
        when(lying.isEmpty()).thenReturn(false);
        when(lying.getOriginalFilename()).thenReturn("cube.stl");
        when(lying.getSize()).thenReturn(1L); // declared size understates the real content
        try {
            when(lying.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }

        var result = service.intake(owner, lying);

        var persistedSize = jdbc.sql("select (payload->'input'->>'sizeBytes')::bigint from geometry_jobs where id=:id")
            .param("id", result.jobId()).query(Long.class).single();
        assertThat(persistedSize).isEqualTo(bytes.length);
    }

    @Test
    void findExistingJobIgnoresACandidateWhoseReferencedAssetOwnerDoesNotMatchTheJobOwner() {
        var owner = insertUser();
        var otherOwner = insertUser();
        var bytes = "solid cube\nendsolid cube".getBytes();
        var sha256 = sha256Hex(bytes);
        var mismatchedAssetId = UUID.randomUUID();
        // Data-integrity violation: the job below is owned by `owner`, but its subject_id points at an
        // asset owned by `otherOwner` — a state the application never produces itself (asset+job are
        // always inserted with the same owner_id in one transaction), modeled here defensively.
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,'UPLOADED','UNKNOWN','assets/mismatched.stl',:sha)")
            .param("id", mismatchedAssetId).param("owner", otherOwner).param("sha", sha256).update();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,'ASSET_PROCESSING',:subject,'PENDING','{}'::jsonb,:key)")
            .param("id", UUID.randomUUID()).param("owner", owner).param("subject", mismatchedAssetId).param("key", sha256).update();

        assertThat(service.findExistingJob(owner, sha256)).isEmpty();
    }

    @Test
    void findExistingJobReturnsAProperlyOwnedMatchAsTriangulation() {
        var owner = insertUser();
        var bytes = "solid cube\nendsolid cube".getBytes();
        var sha256 = sha256Hex(bytes);
        var assetId = UUID.randomUUID();
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,'UPLOADED','UNKNOWN','assets/legit.stl',:sha)")
            .param("id", assetId).param("owner", owner).param("sha", sha256).update();
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,'ASSET_PROCESSING',:subject,'PENDING','{}'::jsonb,:key)")
            .param("id", UUID.randomUUID()).param("owner", owner).param("subject", assetId).param("key", sha256).update();

        assertThat(service.findExistingJob(owner, sha256)).isPresent();
    }

    private java.util.List<java.nio.file.Path> leftoverTempFiles() throws IOException {
        var tmpDir = storageRootPath.resolve("tmp");
        if (!Files.exists(tmpDir)) return java.util.List.of();
        try (var stream = Files.list(tmpDir)) {
            return stream.toList();
        }
    }

    /** Generates an endless stream of a repeated byte without allocating the whole thing; counts bytes actually read. */
    private static final class RepeatingInputStream extends InputStream {
        private final byte fill;
        private final AtomicLong read = new AtomicLong();

        RepeatingInputStream(byte fill) { this.fill = fill; }

        @Override public int read() {
            read.incrementAndGet();
            return fill & 0xFF;
        }

        @Override public int read(byte[] buffer, int offset, int length) {
            Arrays.fill(buffer, offset, offset + length, fill);
            read.addAndGet(length);
            return length;
        }

        long bytesRead() { return read.get(); }
    }

    private long countRows(String table, UUID owner) {
        return jdbc.sql("select count(*) from " + table + " where owner_id=:owner").param("owner", owner).query(Long.class).single();
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'hash')")
            .param("id", id).param("email", "user-" + id + "@example.com").update();
        return id;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
