package com.product.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository securityContexts;

    public AuthController(AuthenticationManager authenticationManager, HttpSessionSecurityContextRepository securityContexts) {
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<Void> login(@RequestBody @Valid LoginRequest request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        var authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.noContent().build();
    }

    record LoginRequest(@Email @NotBlank String email, @NotBlank String password) { }
}
