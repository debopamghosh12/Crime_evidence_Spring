package com.blockevidence.backend.repository;

import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.model.CaseEvidenceLink;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseEvidenceLinkRepository extends JpaRepository<CaseEvidenceLink, UUID> {

    List<CaseEvidenceLink> findByCaseIdOrderByLinkedAtAsc(UUID caseId);
}
