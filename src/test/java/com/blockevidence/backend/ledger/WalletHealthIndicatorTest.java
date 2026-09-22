package com.blockevidence.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.blockevidence.backend.config.FabricProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** A2 (design section 3.5): the "wallet" actuator component. */
class WalletHealthIndicatorTest {

    FabricProperties props(String walletDir) {
        return new FabricProperties("crimechannel", "evidence", "localhost:7051", "peer0.org1.example.com",
                "Org1MSP", "a", "b", "c", walletDir, null, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void unknownWhenNoWalletIsConfiguredAtAll() {
        IdentityStore store = mock(IdentityStore.class);
        Health h = new WalletHealthIndicator(store, props(null)).health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(h.getDetails().get("detail")).asString().contains("No wallet configured").contains("LEDGER_IDENTITY_MISSING");
    }

    @Test
    void upWhenConfiguredAndNothingIsExpiringSoon() {
        IdentityStore store = mock(IdentityStore.class);
        when(store.expiringWithin(30)).thenReturn(List.of());
        Health h = new WalletHealthIndicator(store, props("/wallet")).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWithTheAffectedUserIdsWhenACertificateExpiresSoon() {
        IdentityStore store = mock(IdentityStore.class);
        when(store.expiringWithin(30)).thenReturn(List.of("user-1", "user-2"));
        Health h = new WalletHealthIndicator(store, props("/wallet")).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails().get("userIds")).isEqualTo(List.of("user-1", "user-2"));
        assertThat(h.getDetails().get("detail")).asString().contains("2 wallet identity");
    }
}
