package com.blockevidence.backend.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.blockevidence.backend.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * A1: issues and verifies access tokens. Called by AuthService (issue) and JwtAuthenticationFilter
 * (parse); nothing else in the codebase touches a token.
 *
 * <p>Assumes {@code JwtProperties.secret} has already been validated (>= 32 chars) at startup.
 * Stateless: the claims are trusted until expiry, so a role change or deactivation reaches an
 * already-issued access token only after it expires (15 min by default). Refresh tokens are the
 * revocable part, see AuthService.
 */
@Component
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";

    /** {@code expiresAt} is returned so the caller reports the same instant the token really carries. */
    public record IssuedAccessToken(String token, Instant expiresAt) {
    }

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        // Verification uses the injected clock too, so expiry is testable. Unsigned ("alg: none")
        // tokens are rejected by jjwt by default, and the issuer must match.
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public IssuedAccessToken issueAccessToken(UUID userId, String email, Role role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTtl());
        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, expiresAt);
    }

    /**
     * @throws JwtException             bad signature, expired, wrong issuer, malformed, unsigned
     * @throws IllegalArgumentException the token verified but a claim (sub/role) is not a valid value
     */
    public AuthenticatedUser parse(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_EMAIL, String.class),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
    }
}
