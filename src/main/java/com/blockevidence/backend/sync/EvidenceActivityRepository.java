package com.blockevidence.backend.sync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvidenceActivityRepository extends JpaRepository<EvidenceActivity, UUID> {

    /**
     * G3 idempotency gate (design section 5 step 3a): one atomic statement, so there is no separate
     * check-then-insert race. Returns 1 for a genuinely new event, 0 if this txId was already processed
     * (a redelivery) - the caller must not touch the projection or the checkpoint when this returns 0.
     */
    @Modifying
    @Query(value = """
            INSERT INTO evidence_activity
                (id, tx_id, block_number, evidence_id, version, action, case_id, status, actor_id, actor_role,
                 reason, ledger_at, processed_at)
            VALUES
                (:id, :txId, :blockNumber, :evidenceId, :version, :action, :caseId, :status, :actorId, :actorRole,
                 :reason, :ledgerAt, :processedAt)
            ON CONFLICT (tx_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("txId") String txId, @Param("blockNumber") long blockNumber,
            @Param("evidenceId") String evidenceId, @Param("version") int version, @Param("action") String action,
            @Param("caseId") String caseId, @Param("status") String status, @Param("actorId") String actorId,
            @Param("actorRole") String actorRole, @Param("reason") String reason, @Param("ledgerAt") Instant ledgerAt,
            @Param("processedAt") Instant processedAt);

    List<EvidenceActivity> findByEvidenceIdOrderByVersionAsc(String evidenceId);

    List<EvidenceActivity> findAllByOrderByLedgerAtDesc();
}
