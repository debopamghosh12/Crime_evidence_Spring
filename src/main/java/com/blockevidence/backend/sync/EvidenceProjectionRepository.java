package com.blockevidence.backend.sync;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvidenceProjectionRepository extends JpaRepository<EvidenceProjection, String> {

    /**
     * G3 (design section 5 step 3b): one atomic upsert-with-compare-and-set. {@code WHERE
     * evidence_projection.version <= EXCLUDED.version} means a stale/out-of-order write can never regress the
     * projection - defensive only, since single-consumer checkpoint-sequential processing should never produce one.
     */
    @Modifying
    @Query(value = """
            INSERT INTO evidence_projection
                (evidence_id, case_id, evidence_type, status, version, current_custodian, created_by, created_at,
                 updated_at, last_action, last_reason)
            VALUES
                (:evidenceId, :caseId, :evidenceType, :status, :version, :currentCustodian, :createdBy, :createdAt,
                 :updatedAt, :lastAction, :lastReason)
            ON CONFLICT (evidence_id) DO UPDATE SET
                case_id = EXCLUDED.case_id, evidence_type = EXCLUDED.evidence_type, status = EXCLUDED.status,
                version = EXCLUDED.version, current_custodian = EXCLUDED.current_custodian,
                updated_at = EXCLUDED.updated_at, last_action = EXCLUDED.last_action, last_reason = EXCLUDED.last_reason
            WHERE evidence_projection.version <= EXCLUDED.version
            """, nativeQuery = true)
    void upsert(@Param("evidenceId") String evidenceId, @Param("caseId") String caseId,
            @Param("evidenceType") String evidenceType, @Param("status") String status, @Param("version") int version,
            @Param("currentCustodian") String currentCustodian, @Param("createdBy") String createdBy,
            @Param("createdAt") Instant createdAt, @Param("updatedAt") Instant updatedAt,
            @Param("lastAction") String lastAction, @Param("lastReason") String lastReason);

    List<EvidenceProjection> findByCaseId(String caseId);
}
