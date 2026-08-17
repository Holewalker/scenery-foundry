package com.product.asset;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.product.identity.AuthenticatedUser;

@RestController
public final class AssetController {
    private final AssetIntakeService intakeService;

    public AssetController(AssetIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping("/api/assets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AssetIntakeResult upload(@RequestParam("file") MultipartFile file, Authentication authentication) {
        return intakeService.intake(AuthenticatedUser.from(authentication).userId(), file);
    }
}
