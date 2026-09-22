package com.blockevidence.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.blockevidence.backend.support.FakeContentKeys;
import org.junit.jupiter.api.Test;

/**
 * F2/F3 (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md sections 3-5): wrap/re-wrap/revoke, backed by a REAL
 * UserKeyService (real RSA-2048), so this proves the actual cryptographic round trip, not a mocked one.
 */
class ContentKeyServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final UserKeyService userKeys = FakeContentKeys.userKeyService(clock);
    final ContentKeyService service = FakeContentKeys.contentKeyService(userKeys, clock);

    UUID provisionedUser() {
        UUID id = UUID.randomUUID();
        userKeys.provision(id);
        return id;
    }

    @Test
    void aWrappedKeyUnwrapsBackToTheExactOriginalBytes() {
        UUID user = provisionedUser();
        byte[] eck = service.newKey();

        service.wrapForRegistrant("EV-1", user, eck);

        assertThat(service.unwrap("EV-1", user)).contains(eck);
    }

    @Test
    void anUnwrappedUserGetsNothingRatherThanAnException() {
        UUID neverWrapped = provisionedUser();

        assertThat(service.unwrap("EV-1", neverWrapped)).isEmpty();
    }

    @Test
    void wrappingForARegistrantWithNoKeypairThrowsSoRegistrationAborts() {
        UUID neverProvisioned = UUID.randomUUID();                             // no userKeys.provision() call

        assertThatThrownBy(() -> service.wrapForRegistrant("EV-1", neverProvisioned, service.newKey()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrapBestEffortSwallowsTheSameFailureInsteadOfThrowing() {
        UUID neverProvisioned = UUID.randomUUID();

        assertThatCode(() -> service.wrapBestEffort("EV-1", neverProvisioned, service.newKey())).doesNotThrowAnyException();
        assertThat(service.unwrap("EV-1", neverProvisioned)).isEmpty();
    }

    @Test
    void reWrapForNewUserRecoversTheEckViaAnyExistingHolderAndGrantsAccess() {
        UUID original = provisionedUser();
        UUID newMember = provisionedUser();
        byte[] eck = service.newKey();
        service.wrapForRegistrant("EV-1", original, eck);

        service.reWrapForNewUser("EV-1", newMember);

        assertThat(service.unwrap("EV-1", newMember)).as("the new member unwraps the SAME key, re-wrapped for them")
                .contains(eck);
        assertThat(service.unwrap("EV-1", original)).as("the original holder's own wrapped copy is untouched")
                .contains(eck);
    }

    @Test
    void reWrapForNewUserWithNoExistingHolderDoesNothingRatherThanThrow() {
        UUID newMember = provisionedUser();

        assertThatCode(() -> service.reWrapForNewUser("EV-never-registered", newMember)).doesNotThrowAnyException();
        assertThat(service.unwrap("EV-never-registered", newMember)).isEmpty();
    }

    @Test
    void reWrapForNewUserIsANoOpWhenTheyAlreadyHaveAWrappedCopy() {
        UUID user = provisionedUser();
        byte[] eck = service.newKey();
        service.wrapForRegistrant("EV-1", user, eck);

        assertThatCode(() -> service.reWrapForNewUser("EV-1", user)).doesNotThrowAnyException();

        assertThat(service.unwrap("EV-1", user)).contains(eck);
    }

    @Test
    void revokeRemovesAccessAndIsIdempotent() {
        UUID user = provisionedUser();
        service.wrapForRegistrant("EV-1", user, service.newKey());

        service.revoke("EV-1", user);

        assertThat(service.unwrap("EV-1", user)).as("revoked: not retroactive to already-decrypted content, but no "
                + "new unwrap succeeds (design section 8)").isEmpty();
        assertThatCode(() -> service.revoke("EV-1", user)).as("revoking twice is not an error").doesNotThrowAnyException();
    }

    @Test
    void wrappingTheSameUserTwiceForTheSameEvidenceIsIdempotent() {
        UUID user = provisionedUser();
        byte[] eck = service.newKey();

        service.wrapForRegistrant("EV-1", user, eck);
        service.wrapForRegistrant("EV-1", user, eck);                          // e.g. registrant is also a case member

        assertThat(service.unwrap("EV-1", user)).contains(eck);
    }
}
