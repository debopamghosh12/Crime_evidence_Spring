package com.blockevidence.backend.ledger;

import java.util.List;
import java.util.Optional;

import com.blockevidence.backend.config.FabricProperties;
import org.springframework.stereotype.Service;

/**
 * G1 STUB. The Hyperledger Fabric implementation of LedgerService. In Phase 1 it exists so the rest
 * of the application can be wired against the interface; every operation throws
 * LedgerNotImplementedException. It imports nothing from Fabric on purpose: the fabric-gateway
 * dependency is added only when this class is really implemented.
 */
@Service
public class FabricLedgerService implements LedgerService {

    private final FabricProperties properties;

    public FabricLedgerService(FabricProperties properties) {
        this.properties = properties;
    }

    @Override
    public LedgerTxResult createEvidence(String evidenceId, String caseId, String cid, String sha256, String actorId) {
        throw new LedgerNotImplementedException("createEvidence");
    }

    @Override
    public LedgerTxResult updateEvidence(String evidenceId, String newCid, String newSha256, String reason,
            String actorId) {
        throw new LedgerNotImplementedException("updateEvidence");
    }

    @Override
    public LedgerTxResult updateStatus(String evidenceId, String newStatus, String reason, String actorId) {
        throw new LedgerNotImplementedException("updateStatus");
    }

    @Override
    public LedgerTxResult initiateTransfer(String evidenceId, String toUserId, String reason, String actorId) {
        throw new LedgerNotImplementedException("initiateTransfer");
    }

    @Override
    public LedgerTxResult acceptTransfer(String evidenceId, String actorId) {
        throw new LedgerNotImplementedException("acceptTransfer");
    }

    @Override
    public Optional<LedgerEvidenceRecord> getEvidence(String evidenceId) {
        throw new LedgerNotImplementedException("getEvidence");
    }

    @Override
    public List<LedgerHistoryEntry> getHistory(String evidenceId) {
        throw new LedgerNotImplementedException("getHistory");
    }

    /** UNKNOWN, not DOWN: nothing is broken, the connection simply has not been built yet. */
    @Override
    public LedgerHealth health() {
        return new LedgerHealth(LedgerHealth.State.UNKNOWN,
                "FabricLedgerService not implemented (Phase 1 stub); configured channel="
                        + properties.channel() + " chaincode=" + properties.chaincode());
    }
}
