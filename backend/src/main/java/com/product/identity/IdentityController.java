package com.product.identity;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class IdentityController {
    @GetMapping("/api/auth/me")
    Map<String, String> currentUser(Authentication authentication) {
        var user = AuthenticatedUser.from(authentication);
        return Map.of("userId", user.userId().toString(), "email", user.email());
    }
}
