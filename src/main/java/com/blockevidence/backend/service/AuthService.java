package com.blockevidence.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.blockevidence.backend.config.JwtProperties;
import com.blockevidence.backend.dto.LoginRequest;
import com.blockevidence.backend.dto.MeResponse;
import com.blockevidence.backend.dto.TokenResponse;
import com.blockevidence.backend.exception.AuthenticationFailedException;
import com.blockevidence.backend.model.RefreshToken;
import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.RefreshTokenRepository;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A1: login, refresh-token rotation, logout. Called by AuthController.
 *
 * <p>Access tokens are stateless JWTs (JwtService). Refresh tokens are opaque random values stored
 * server-side as a hash, so they can be revoked and rotated: each use retires the presented token
 * and issues a new one. Presenting an already-retired token means it was copied, so every session
 * of that user is revoked.
 */
@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "Invalid email or password";
    private static final String BAD_REFRESH = "Invalid or expired refresh token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public AuthService(AuthenticationManager authenticationManager, UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository, JwtService jwtService, JwtProperties jwtProperties,
            Clock clock) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (InternalAuthenticationServiceException e) {
            // A real failure (e.g. database down) must surface as a 500, not be reported to the
            // caller as "wrong password".
            throw e;
        } catch (AuthenticationException e) {
            // Bad password, unknown user and disabled account all look identical to the caller.
            throw new AuthenticationFailedException(BAD_CREDENTIALS);
        }
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new AuthenticationFailedException(BAD_CREDENTIALS));
        return issueTokens(user);
    }

    /**
     * noRollbackFor is essential: when reuse is detected we revoke the user's sessions and THEN throw.
     * With default rollback the revocation would be undone by the very exception that reports it.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public TokenResponse refresh(String presentedToken) {
        Instant now = clock.instant();
        RefreshToken stored = refreshTokenRepository.findByTokenHash(sha256Hex(presentedToken))
                .orElseThrow(() -> new AuthenticationFailedException(BAD_REFRESH));

        if (stored.getRevokedAt() != null) {
            revokeAllAndReject(stored, now);
        }
        if (!stored.getExpiresAt().isAfter(now)) {
            throw new AuthenticationFailedException(BAD_REFRESH);
        }
        // Atomic claim: if two refreshes race with the same token, exactly one gets 1 here.
        if (refreshTokenRepository.revokeIfActive(stored.getId(), now) == 0) {
            revokeAllAndReject(stored, now);
        }

        User user = userRepository.findById(stored.getUserId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            // Deactivation (A4) must cut off refresh immediately, whatever tokens are still live.
            revokeAllAndReject(stored, now);
        }
        return issueTokens(user);
    }

    @Transactional
    public void logout(UUID userId, String presentedToken) {
        // Only the owner may revoke, and an unknown or foreign token is a silent no-op: logout must
        // not become a way to probe which refresh tokens exist.
        refreshTokenRepository.findByTokenHash(sha256Hex(presentedToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> refreshTokenRepository.revokeIfActive(token.getId(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> new MeResponse(u.getId(), u.getEmail(), u.getFullName(), u.getDepartment(), u.getRole()))
                .orElseThrow(() -> new AuthenticationFailedException("Account no longer exists"));
    }

    private void revokeAllAndReject(RefreshToken stored, Instant now) {
        refreshTokenRepository.revokeAllForUser(stored.getUserId(), now);
        throw new AuthenticationFailedException(BAD_REFRESH);
    }

    private TokenResponse issueTokens(User user) {
        Instant now = clock.instant();
        JwtService.IssuedAccessToken access = jwtService.issueAccessToken(user.getId(), user.getEmail(), user.getRole());

        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        refreshTokenRepository.save(new RefreshToken(
                user.getId(), sha256Hex(refreshToken), now.plus(jwtProperties.refreshTtl()), now));

        return TokenResponse.bearer(access.token(), refreshToken, Duration.between(now, access.expiresAt()).toSeconds());
    }

    // Plain SHA-256 (not BCrypt) is correct here: the input is 256 bits of randomness, so there is no
    // weak secret to brute-force, and the lookup must be by hash, which a salted hash would prevent.
    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java spec", e);
        }
    }
}
