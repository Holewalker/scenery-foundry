package com.product.asset;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.product.identity.AuthenticatedUser;
import com.product.scene.AssetProcessingStatus;
import com.product.scene.OwnedResourceNotFoundException;
import com.product.storage.StorageResolver;

/** Owner-scoped async intake plus catalog reads (design's API Surface table: POST/GET /api/assets*). */
@RestController
public final class AssetController {
    private final AssetIntakeService intakeService;
    private final JdbcAssetRepository catalogRepository;
    private final StorageResolver storageResolver;

    public AssetController(AssetIntakeService intakeService, JdbcAssetRepository catalogRepository, StorageResolver storageResolver) {
        this.intakeService = intakeService;
        this.catalogRepository = catalogRepository;
        this.storageResolver = storageResolver;
    }

    @PostMapping("/api/assets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AssetIntakeResult upload(@RequestParam("file") MultipartFile file, Authentication authentication) {
        return intakeService.intake(AuthenticatedUser.from(authentication).userId(), file);
    }

    @GetMapping("/api/assets")
    List<AssetCatalogEntry> list(Authentication authentication) {
        return catalogRepository.findCatalogForOwner(AuthenticatedUser.from(authentication).userId());
    }

    @GetMapping("/api/assets/{assetId}")
    AssetCatalogEntry get(@PathVariable UUID assetId, Authentication authentication) {
        return findOwned(assetId, authentication);
    }

    @GetMapping(value = "/api/assets/{assetId}/original", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    byte[] original(@PathVariable UUID assetId, Authentication authentication) {
        var entry = findOwned(assetId, authentication);
        return storageResolver.readBytes(entry.originalStorageKey());
    }

    @GetMapping(value = "/api/assets/{assetId}/preview", produces = "model/gltf-binary")
    byte[] preview(@PathVariable UUID assetId, Authentication authentication) {
        var entry = findOwned(assetId, authentication);
        if (entry.processingStatus() != AssetProcessingStatus.READY || entry.previewStorageKey() == null) {
            throw new OwnedResourceNotFoundException();
        }
        return storageResolver.readBytes(entry.previewStorageKey());
    }

    private AssetCatalogEntry findOwned(UUID assetId, Authentication authentication) {
        var ownerId = AuthenticatedUser.from(authentication).userId();
        return catalogRepository.findByOwnerAndId(ownerId, assetId).orElseThrow(OwnedResourceNotFoundException::new);
    }
}
