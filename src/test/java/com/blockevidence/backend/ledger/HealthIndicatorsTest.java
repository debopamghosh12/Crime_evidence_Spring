package com.blockevidence.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blockevidence.backend.storage.IpfsClient;
import com.blockevidence.backend.storage.IpfsHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** G4 health mapping. (FabricLedgerService's own behaviour is in FabricLedgerServiceTest.) */
class HealthIndicatorsTest {

    @Test
    void ledgerIndicatorMapsAllThreeStates() {
        LedgerService ledger = mock(LedgerService.class);
        when(ledger.health()).thenReturn(new LedgerHealth(LedgerHealth.State.UP, "ok"));
        assertThat(new LedgerHealthIndicator(ledger).health().getStatus()).isEqualTo(Status.UP);
        when(ledger.health()).thenReturn(new LedgerHealth(LedgerHealth.State.DOWN, "gateway refused"));
        assertThat(new LedgerHealthIndicator(ledger).health().getStatus()).isEqualTo(Status.DOWN);
        when(ledger.health()).thenReturn(new LedgerHealth(LedgerHealth.State.UNKNOWN, "not configured"));
        Health unknown = new LedgerHealthIndicator(ledger).health();
        assertThat(unknown.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(unknown.getDetails().get("detail")).isEqualTo("not configured");
    }

    @Test
    void ipfsIndicatorMapsReachableToUpAndUnreachableToDown() {
        IpfsClient ipfs = mock(IpfsClient.class);
        when(ipfs.isReachable()).thenReturn(true);
        assertThat(new IpfsHealthIndicator(ipfs).health().getStatus()).isEqualTo(Status.UP);
        when(ipfs.isReachable()).thenReturn(false);
        assertThat(new IpfsHealthIndicator(ipfs).health().getStatus()).isEqualTo(Status.DOWN);
    }

    /**
     * Verifies the ARCHITECTURE.md 4.4 claim: an UNKNOWN ledger must not drag overall health down
     * while db and ipfs are UP, but a genuine DOWN elsewhere must still win.
     */
    @Test
    void springAggregationIgnoresUnknownWhenOthersAreUpButNotDown() {
        StatusAggregator aggregator = StatusAggregator.getDefault();

        assertThat(aggregator.getAggregateStatus(Status.UP, Status.UP, Status.UNKNOWN)).isEqualTo(Status.UP);
        assertThat(aggregator.getAggregateStatus(Status.UP, Status.DOWN, Status.UNKNOWN)).isEqualTo(Status.DOWN);
    }
}
