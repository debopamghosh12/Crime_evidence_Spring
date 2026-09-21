package com.blockevidence.backend.controller;

import com.blockevidence.backend.dto.LoginRequest;
import com.blockevidence.backend.dto.MeResponse;
import com.blockevidence.backend.dto.RefreshTokenRequest;
import com.blockevidence.backend.dto.TokenResponse;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A1. Login/refresh are public (see SecurityConfig); logout and /me need a valid access token.
 * Binding and validation only: the logic lives in AuthService.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(user.userId(), request.refreshToken());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.me(user.userId());
    }
}
