package com.blockevidence.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import com.blockevidence.backend.TestSecrets;
import com.blockevidence.backend.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    static final String SECRET = TestSecrets.JWT_SECRET;
    static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    static JwtProperties props(String secret, String issuer) {
        return new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), issuer);
    }

    static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    final JwtService service = new JwtService(props(SECRET, "be"), clockAt(T0));
    final UUID userId = UUID.randomUUID();

    @Test
    void issuedTokenRoundTripsToTheSamePrincipal() {
        JwtService.IssuedAccessToken issued = service.issueAccessToken(userId, "a@b.c", Role.JUDGE);

        AuthenticatedUser user = service.parse(issued.token());

        assertThat(user).isEqualTo(new AuthenticatedUser(userId, "a@b.c", Role.JUDGE));
        assertThat(issued.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service.issueAccessToken(userId, "a@b.c", Role.ADMIN).token();
        JwtService sixteenMinutesLater = new JwtService(props(SECRET, "be"), clockAt(T0.plus(Duration.ofMinutes(16))));

        assertThatThrownBy(() -> sixteenMinutesLater.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenStillValidJustBeforeExpiry() {
        String token = service.issueAccessToken(userId, "a@b.c", Role.ADMIN).token();
        JwtService fourteenMinutesLater = new JwtService(props(SECRET, "be"), clockAt(T0.plus(Duration.ofMinutes(14))));

        assertThat(fourteenMinutesLater.parse(token).userId()).isEqualTo(userId);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = service.issueAccessToken(userId, "a@b.c", Role.COLLECTOR).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService other = new JwtService(props(TestSecrets.OTHER_SECRET, "be"), clockAt(T0));
        String forged = other.issueAccessToken(userId, "a@b.c", Role.ADMIN).token();

        assertThatThrownBy(() -> service.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtService other = new JwtService(props(SECRET, "someone-else"), clockAt(T0));
        String token = other.issueAccessToken(userId, "a@b.c", Role.ADMIN).token();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        // Classic attack: strip the signature and claim no algorithm.
        String unsigned = Jwts.builder().issuer("be").subject(userId.toString()).claim("role", "ADMIN")
                .expiration(Date.from(T0.plus(Duration.ofHours(1)))).compact();

        assertThatThrownBy(() -> service.parse(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void validlySignedTokenWithUnknownRoleIsRejected() {
        String token = Jwts.builder().issuer("be").subject(userId.toString()).claim("role", "SUPERUSER")
                .expiration(Date.from(T0.plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> service.parse("not.a.jwt")).isInstanceOf(JwtException.class);
    }
}
