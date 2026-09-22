package com.blockevidence.backend.controller;

import com.blockevidence.backend.dto.LoginRequest;
import com.blockevidence.backend.dto.MeResponse;
import com.blockevidence.backend.dto.RefreshTokenRequest;
import com.blockevidence.backend.dto.TokenResponse;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Auth", description = "Login, refresh-token rotation, logout. No role requirement.")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @SecurityRequirements // public: overrides the global bearerAuth requirement for this one endpoint
    @Operation(summary = "Log in", description = """
            Public - no token needed. Returns a 15-minute access token and a rotating refresh token (7 days). \
            401 on a wrong password or a disabled account; both answer with the same generic message \
            (constraint: never reveal which one it was).""")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @SecurityRequirements // public: the refresh token itself is the credential, not a bearer access token
    @Operation(summary = "Rotate the refresh token", description = """
            Public - the refresh token in the body is the credential. Each refresh token is single-use: \
            presenting an already-used or unknown one revokes every refresh token the user holds (reuse is \
            treated as presumed theft) and answers 401.""")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Log out", description = "Revokes the given refresh token if it belongs to the "
            + "caller; otherwise a silent no-op (204 either way - logout never confirms whether a token exists).")
    @ApiResponse(responseCode = "204", description = "Always, whether or not the token was valid", content = @Content)
    public void logout(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(user.userId(), request.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "Current user", description = "The caller's own profile, from the verified access "
            + "token - never the password hash.")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.me(user.userId());
    }
}
