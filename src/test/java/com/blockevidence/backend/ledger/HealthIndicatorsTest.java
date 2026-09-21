package com.blockevidence.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.blockevidence.backend.config.FabricProperties;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.support.FakeIpfsClient;
import com.blockevidence.backend.storage.IpfsClient;
import com.blockevidence.backend.storage.IpfsHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** G1 stub behaviour and G4 health mapping. */
class HealthIndicatorsTest {

    final FabricLedgerService stub = new FabricLedgerService(new FabricProperties("crimechannel", "basic"));

    @Test
    void fabricStubReportsUnknownWithItsConfigurationNotDown() {
        Health health = new LedgerHealthIndicator(stub).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails().get("detail").toString())
                .contains("not implemented").contains("crimechannel").contains("basic");
    }

    @Test
    void everyFabricStubOperationThrowsNotImplemented() {
        LedgerActor actor = new LedgerActor(UUID.randomUUID().toString(), Role.COLLECTOR);
        String id = "EV-" + UUID.randomUUID();
        String cid = FakeIpfsClient.cidOf("x".getBytes());
        var evidence = new LedgerNewEvidence(id, "C", EvidenceType.PHYSICAL, cid, "a".repeat(64), null, null, null);

        assertThatThrownBy(() -> stub.createEvidence(evidence, actor)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.updateEvidence(id, 1, cid, "a".repeat(64), "r", actor)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.updateStatus(id, 1, EvidenceStatus.PROCESSING, "r", actor)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.requestDisposal(id, 1, "r", actor)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.approveDisposal(id, 1, "r", actor)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.rejectDisposal(id, 1, "r", actor)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.getEvidence(id)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.getHistory(id)).isInstanceOf(LedgerNotImplementedException.class);
        assertThatThrownBy(() -> stub.findEvidenceIdsByCid(cid)).isInstanceOf(LedgerNotImplementedException.class);
    }

    @Test
    void ledgerIndicatorMapsAllThreeStates() {
        LedgerService ledger = mock(LedgerService.class);
        when(ledger.health()).thenReturn(new LedgerHealth(LedgerHealth.State.UP, "ok"));
        assertThat(new LedgerHealthIndicator(ledger).health().getStatus()).isEqualTo(Status.UP);
        when(ledger.health()).thenReturn(new LedgerHealth(LedgerHealth.State.DOWN, "gateway refused"));
        assertThat(new LedgerHealthIndicator(ledger).health().getStatus()).isEqualTo(Status.DOWN);
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
