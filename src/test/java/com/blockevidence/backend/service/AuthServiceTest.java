package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.TestSecrets;
import com.blockevidence.backend.config.JwtProperties;
import com.blockevidence.backend.dto.LoginRequest;
import com.blockevidence.backend.dto.TokenResponse;
import com.blockevidence.backend.exception.AuthenticationFailedException;
import com.blockevidence.backend.model.RefreshToken;
import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.RefreshTokenRepository;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.JwtService;
import com.blockevidence.backend.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    final AuthenticationManager authManager = mock(AuthenticationManager.class);
    final UserRepository users = mock(UserRepository.class);
    final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final JwtProperties props = new JwtProperties(
            TestSecrets.JWT_SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "be");
    // The real JwtService, not a mock: the tests then prove the tokens AuthService issues actually verify.
    final JwtService jwt = new JwtService(props, clock);
    final AuthService service = new AuthService(authManager, users, tokens, jwt, props, clock);

    final UUID userId = UUID.randomUUID();
    User user;

    @BeforeEach
    void setUp() {
        user = new User("officer@example.com", "hash", "Officer", "Dept", Role.COLLECTOR, NOW);
        ReflectionTestUtils.setField(user, "id", userId);
    }

    RefreshToken storedToken(String raw, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken(userId, AuthService.sha256Hex(raw), expiresAt, NOW);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(token, "revokedAt", revokedAt);
        return token;
    }

    // ------------------------------------------------------------------ login

    @Test
    void loginIssuesVerifiableAccessTokenAndStoresOnlyTheRefreshHash() {
        when(users.findByEmailIgnoreCase("officer@example.com")).thenReturn(Optional.of(user));

        TokenResponse response = service.login(new LoginRequest("officer@example.com", "pw"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(jwt.parse(response.accessToken()))
                .isEqualTo(new AuthenticatedUser(userId, "officer@example.com", Role.COLLECTOR));

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(AuthService.sha256Hex(response.refreshToken()));
        assertThat(saved.getValue().getTokenHash()).isNotEqualTo(response.refreshToken());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledAccountAreIndistinguishable() {
        for (RuntimeException failure : new RuntimeException[] {
                new BadCredentialsException("x"), new DisabledException("x") }) {
            // doThrow(...).when(...) rather than when(...): re-stubbing would call the mock, which already throws.
            doThrow(failure).when(authManager).authenticate(any());

            assertThatThrownBy(() -> service.login(new LoginRequest("a@b.c", "pw")))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessage("Invalid email or password");
        }
        verify(tokens, never()).save(any());
    }

    @Test
    void infrastructureFailureDuringLoginIsNotReportedAsBadCredentials() {
        var dbDown = new InternalAuthenticationServiceException("db down");
        when(authManager.authenticate(any())).thenThrow(dbDown);

        assertThatThrownBy(() -> service.login(new LoginRequest("a@b.c", "pw"))).isSameAs(dbDown);
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refreshRotatesTheTokenAndIssuesANewPair() {
        RefreshToken stored = storedToken("old-token", NOW.plusSeconds(60), null);
        when(tokens.findByTokenHash(AuthService.sha256Hex("old-token"))).thenReturn(Optional.of(stored));
        when(tokens.revokeIfActive(stored.getId(), NOW)).thenReturn(1);
        when(users.findById(userId)).thenReturn(Optional.of(user));

        TokenResponse response = service.refresh("old-token");

        verify(tokens).revokeIfActive(stored.getId(), NOW);
        verify(tokens).save(any(RefreshToken.class));
        assertThat(response.refreshToken()).isNotEqualTo("old-token");
        assertThat(jwt.parse(response.accessToken()).userId()).isEqualTo(userId);
    }

    @Test
    void refreshWithUnknownTokenIsRejectedWithoutTouchingAnySession() {
        when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("nope")).isInstanceOf(AuthenticationFailedException.class);

        verify(tokens, never()).revokeAllForUser(any(), any());
    }

    @Test
    void reusingARetiredRefreshTokenRevokesEverySessionOfThatUser() {
        RefreshToken retired = storedToken("stolen", NOW.plusSeconds(60), NOW.minusSeconds(5));
        when(tokens.findByTokenHash(AuthService.sha256Hex("stolen"))).thenReturn(Optional.of(retired));

        assertThatThrownBy(() -> service.refresh("stolen")).isInstanceOf(AuthenticationFailedException.class);

        verify(tokens).revokeAllForUser(userId, NOW);
        verify(tokens, never()).save(any());
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        RefreshToken expired = storedToken("old", NOW.minusSeconds(1), null);
        when(tokens.findByTokenHash(AuthService.sha256Hex("old"))).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.refresh("old")).isInstanceOf(AuthenticationFailedException.class);

        verify(tokens, never()).revokeIfActive(any(), any());
        verify(tokens, never()).save(any());
    }

    @Test
    void losingTheRaceForTheSameTokenIsTreatedAsReuse() {
        RefreshToken stored = storedToken("racy", NOW.plusSeconds(60), null);
        when(tokens.findByTokenHash(AuthService.sha256Hex("racy"))).thenReturn(Optional.of(stored));
        when(tokens.revokeIfActive(stored.getId(), NOW)).thenReturn(0); // a concurrent request got there first

        assertThatThrownBy(() -> service.refresh("racy")).isInstanceOf(AuthenticationFailedException.class);

        verify(tokens).revokeAllForUser(userId, NOW);
        verify(tokens, never()).save(any());
    }

    @Test
    void refreshForADeactivatedUserIsRejectedAndKillsTheirSessions() {
        ReflectionTestUtils.setField(user, "enabled", false);
        RefreshToken stored = storedToken("valid", NOW.plusSeconds(60), null);
        when(tokens.findByTokenHash(AuthService.sha256Hex("valid"))).thenReturn(Optional.of(stored));
        when(tokens.revokeIfActive(stored.getId(), NOW)).thenReturn(1);
        when(users.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.refresh("valid")).isInstanceOf(AuthenticationFailedException.class);

        verify(tokens).revokeAllForUser(userId, NOW);
        verify(tokens, never()).save(any());
    }

    // ----------------------------------------------------------------- logout

    @Test
    void logoutRevokesTheOwnersToken() {
        RefreshToken stored = storedToken("mine", NOW.plusSeconds(60), null);
        when(tokens.findByTokenHash(AuthService.sha256Hex("mine"))).thenReturn(Optional.of(stored));

        service.logout(userId, "mine");

        verify(tokens).revokeIfActive(stored.getId(), NOW);
    }

    @Test
    void logoutWithSomeoneElsesTokenIsASilentNoOp() {
        RefreshToken stored = storedToken("theirs", NOW.plusSeconds(60), null);
        when(tokens.findByTokenHash(AuthService.sha256Hex("theirs"))).thenReturn(Optional.of(stored));

        service.logout(UUID.randomUUID(), "theirs");

        verify(tokens, never()).revokeIfActive(any(), any());
    }

    // --------------------------------------------------------------------- me

    @Test
    void meReturnsProfileWithoutPasswordHash() {
        when(users.findById(userId)).thenReturn(Optional.of(user));

        var me = service.me(userId);

        assertThat(me.email()).isEqualTo("officer@example.com");
        assertThat(me.role()).isEqualTo(Role.COLLECTOR);
        assertThat(me.toString()).doesNotContain("hash");
    }

    @Test
    void meForADeletedAccountIsUnauthenticated() {
        when(users.findById(eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(userId)).isInstanceOf(AuthenticationFailedException.class);
    }
}
