package com.blockevidence.backend.crypto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceContentKeyRepository extends JpaRepository<EvidenceContentKey, UUID> {

    Optional<EvidenceContentKey> findByEvidenceIdAndUserId(String evidenceId, UUID userId);

    boolean existsByEvidenceIdAndUserId(String evidenceId, UUID userId);

    List<EvidenceContentKey> findByEvidenceId(String evidenceId);

    /** Picks any one existing wrapped copy to re-wrap from (F3's re-wrap-on-access-grant, section 5) - which
     *  one does not matter, they all unwrap to the same raw ECK. */
    Optional<EvidenceContentKey> findFirstByEvidenceId(String evidenceId);

    void deleteByEvidenceIdAndUserId(String evidenceId, UUID userId);
}
