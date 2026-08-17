package com.product.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.product.scene.AssetProcessingStatus;
import com.product.storage.StorageAccessException;
import com.product.storage.StorageResolver;

/** Owner-scoped async intake: persists the original STL and creates {@code assets}+{@code geometry_jobs} in one tx; no mesh parsing. */
@Service
public class AssetIntakeService {
    private static final long MAX_FILE_SIZE_BYTES = 200L * 1024 * 1024;
    private static final String JOB_TYPE = "ASSET_PROCESSING";

    private final JdbcClient jdbc;
    private final StorageResolver storageResolver;

    public AssetIntakeService(JdbcClient jdbc, StorageResolver storageResolver) {
        this.jdbc = jdbc;
        this.storageResolver = storageResolver;
    }

    @Transactional
    public AssetIntakeResult intake(UUID ownerId, MultipartFile file) {
        validate(file);
        byte[] bytes = readBytes(file);
        String sha256 = sha256Hex(bytes);

        var existing = findExistingJob(ownerId, sha256);
        if (existing.isPresent()) {
            var match = existing.get();
            if (!sha256.equals(match.originalSha256())) throw new IdempotencyConflictException();
            return new AssetIntakeResult(match.assetId(), match.processingStatus(), match.jobId());
        }

        var assetId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var storageKey = storageResolver.allocateKey("assets/" + assetId, file.getOriginalFilename());
        publish(bytes, storageKey);
        insertAsset(assetId, ownerId, storageKey, sha256);
        insertJob(jobId, ownerId, assetId, storageKey, sha256, bytes.length);

        return new AssetIntakeResult(assetId, AssetProcessingStatus.UPLOADED, jobId);
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new UnsupportedAssetMediaTypeException();
        var filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".stl")) throw new UnsupportedAssetMediaTypeException();
        if (file.getSize() > MAX_FILE_SIZE_BYTES) throw new AssetTooLargeException();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new StorageAccessException("upload stream");
        }
    }

    private void publish(byte[] bytes, String storageKey) {
        try {
            var temp = Files.createTempFile("asset-upload-", ".tmp");
            Files.write(temp, bytes);
            storageResolver.publish(temp, storageKey);
        } catch (IOException exception) {
            throw new StorageAccessException(storageKey);
        }
    }

    private void insertAsset(UUID assetId, UUID ownerId, String storageKey, String sha256) {
        jdbc.sql("insert into assets(id,owner_id,processing_status,geometry_status,storage_key,original_sha256) "
                + "values (:id,:owner,'UPLOADED','UNKNOWN',:storageKey,:sha256)")
            .param("id", assetId).param("owner", ownerId).param("storageKey", storageKey).param("sha256", sha256).update();
    }

    private void insertJob(UUID jobId, UUID ownerId, UUID assetId, String storageKey, String sha256, long sizeBytes) {
        jdbc.sql("insert into geometry_jobs(id,owner_id,job_type,subject_id,status,payload,idempotency_key) "
                + "values (:id,:owner,:jobType,:subject,'PENDING',:payload::jsonb,:idempotencyKey)")
            .param("id", jobId).param("owner", ownerId).param("jobType", JOB_TYPE).param("subject", assetId)
            .param("payload", payloadJson(jobId, assetId, storageKey, sha256, sizeBytes))
            .param("idempotencyKey", sha256).update();
    }

    private Optional<ExistingJob> findExistingJob(UUID ownerId, String sha256) {
        return jdbc.sql("select j.id as job_id, j.subject_id as asset_id, a.processing_status, a.original_sha256 "
                + "from geometry_jobs j join assets a on a.id=j.subject_id "
                + "where j.owner_id=:owner and j.job_type=:jobType and j.idempotency_key=:key")
            .param("owner", ownerId).param("jobType", JOB_TYPE).param("key", sha256)
            .query((row, index) -> new ExistingJob(row.getObject("job_id", UUID.class), row.getObject("asset_id", UUID.class),
                AssetProcessingStatus.valueOf(row.getString("processing_status")), row.getString("original_sha256")))
            .optional();
    }

    /** ADR-0002 payload envelope v1. All values are server-generated (UUIDs, validated extensions) — no user input reaches this string unescaped. */
    private static String payloadJson(UUID jobId, UUID assetId, String storageKey, String sha256, long sizeBytes) {
        return "{\"contract\":\"scenery-foundry.geometry-job\",\"version\":1,\"jobType\":\"" + JOB_TYPE + "\","
            + "\"jobId\":\"" + jobId + "\",\"subjectId\":\"" + assetId + "\","
            + "\"input\":{\"storageKey\":\"" + storageKey + "\",\"sha256\":\"" + sha256 + "\",\"sizeBytes\":" + sizeBytes + "},"
            + "\"output\":{\"directory\":\"assets/" + assetId + "\"},\"options\":{\"geometryPolicyVersion\":1}}";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private record ExistingJob(UUID jobId, UUID assetId, AssetProcessingStatus processingStatus, String originalSha256) { }
}
