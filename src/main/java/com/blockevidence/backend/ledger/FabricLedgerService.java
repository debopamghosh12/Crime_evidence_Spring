package com.blockevidence.backend.ledger;

import java.util.List;
import java.util.Optional;

import com.blockevidence.backend.config.FabricProperties;
import com.blockevidence.backend.domain.EvidenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * G1/G2 STUB. The Hyperledger Fabric implementation of LedgerService. Every operation throws
 * LedgerNotImplementedException until the chaincode design (docs/CHAINCODE_DESIGN.md) is approved and
 * implemented; it imports nothing from Fabric on purpose, because the fabric-gateway dependency is added
 * only when this class is really implemented.
 *
 * <p>Not active under the {@code memory-ledger} profile, where InMemoryLedgerService takes its place.
 */
@Service
@Profile("!memory-ledger")
public class FabricLedgerService implements LedgerService {

    private final FabricProperties properties;

    public FabricLedgerService(FabricProperties properties) {
        this.properties = properties;
    }

    @Override
    public LedgerTxResult createEvidence(LedgerNewEvidence evidence, LedgerActor actor) {
        throw new LedgerNotImplementedException("createEvidence");
    }

    @Override
    public LedgerTxResult updateEvidence(String evidenceId, int expectedVersion, String newMetadataCid,
            String newMetadataSha256, String reason, LedgerActor actor) {
        throw new LedgerNotImplementedException("updateEvidence");
    }

    @Override
    public LedgerTxResult updateStatus(String evidenceId, int expectedVersion, EvidenceStatus newStatus,
            String reason, LedgerActor actor) {
        throw new LedgerNotImplementedException("updateStatus");
    }

    @Override
    public LedgerTxResult requestDisposal(String evidenceId, int expectedVersion, String reason, LedgerActor actor) {
        throw new LedgerNotImplementedException("requestDisposal");
    }

    @Override
    public LedgerTxResult approveDisposal(String evidenceId, int expectedVersion, String note, LedgerActor actor) {
        throw new LedgerNotImplementedException("approveDisposal");
    }

    @Override
    public LedgerTxResult rejectDisposal(String evidenceId, int expectedVersion, String note, LedgerActor actor) {
        throw new LedgerNotImplementedException("rejectDisposal");
    }

    @Override
    public Optional<LedgerEvidenceRecord> getEvidence(String evidenceId) {
        throw new LedgerNotImplementedException("getEvidence");
    }

    @Override
    public List<LedgerHistoryEntry> getHistory(String evidenceId) {
        throw new LedgerNotImplementedException("getHistory");
    }

    @Override
    public List<String> findEvidenceIdsByCid(String cid) {
        throw new LedgerNotImplementedException("findEvidenceIdsByCid");
    }

    /** UNKNOWN, not DOWN: nothing is broken, the connection simply has not been built yet. */
    @Override
    public LedgerHealth health() {
        return new LedgerHealth(LedgerHealth.State.UNKNOWN,
                "FabricLedgerService not implemented (stub); configured channel="
                        + properties.channel() + " chaincode=" + properties.chaincode());
    }
}
