package com.blockevidence.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A6. {@link AuditService#recordAccess} is the one paired with the Phase 1 access-token-lag gap: it re-checks
 * {@code user.enabled} at write time (not trusting the JWT's claims) and records
 * {@link AuditAction#TOKEN_USED_AFTER_DEACTIVATION} instead of the ordinary VIEW/DOWNLOAD when the acting user
 * has since been disabled - the exact behaviour the live verification (TEST_CHECKLIST) demonstrates end to end.
 */
class AuditServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final AuditLogRepository repository = mock(AuditLogRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final AuditService service = new AuditService(repository, users, clock);

    final UUID userId = UUID.randomUUID();
    final AuthenticatedUser principal = new AuthenticatedUser(userId, "collector@example.org", Role.COLLECTOR);

    void authenticateAs(AuthenticatedUser user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }

    User enabled(boolean isEnabled) {
        User u = new User("collector@example.org", "h", "N", "D", Role.COLLECTOR, clock.instant());
        ReflectionTestUtils.setField(u, "id", userId);
        ReflectionTestUtils.setField(u, "enabled", isEnabled);
        return u;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aStillEnabledUserGetsAnOrdinaryViewOrDownloadEntry() {
        authenticateAs(principal);
        when(users.findById(userId)).thenReturn(Optional.of(enabled(true)));

        service.recordAccess("EV-1", false);
        service.recordAccess("EV-2", true);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLog::getAction)
                .containsExactly(AuditAction.VIEW, AuditAction.DOWNLOAD);
        assertThat(captor.getAllValues()).extracting(AuditLog::getUserId).containsOnly(userId);
    }

    @Test
    void aDeactivatedUsersStillValidTokenIsRecordedAsTokenUsedAfterDeactivationNotAnOrdinaryView() {
        authenticateAs(principal);
        when(users.findById(userId)).thenReturn(Optional.of(enabled(false)));

        service.recordAccess("EV-1", false);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(AuditAction.TOKEN_USED_AFTER_DEACTIVATION);
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getResource()).isEqualTo("EV-1");
    }

    @Test
    void aUserDeletedOutrightIsTreatedTheSameAsDeactivated() {
        authenticateAs(principal);
        when(users.findById(userId)).thenReturn(Optional.empty());

        service.recordAccess("EV-1", false);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(AuditAction.TOKEN_USED_AFTER_DEACTIVATION);
    }

    @Test
    void recordAccessWithNoAuthenticatedPrincipalDoesNothing() {
        SecurityContextHolder.clearContext();
        service.recordAccess("EV-1", false);
        verify(repository, never()).save(any());
    }

    @Test
    void deniedEntriesCarryWhicheverPrincipalIsPresentOrNoneAtAll() {
        authenticateAs(principal);
        service.recordDenied(AuditAction.ACCESS_DENIED, "/api/evidence/EV-1", "no permission", "10.0.0.5");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getIpAddress()).isEqualTo("10.0.0.5");

        SecurityContextHolder.clearContext();
        service.recordDenied(AuditAction.AUTH_FAILED, "/api/evidence/EV-1", "no token", "10.0.0.6");
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }
}
