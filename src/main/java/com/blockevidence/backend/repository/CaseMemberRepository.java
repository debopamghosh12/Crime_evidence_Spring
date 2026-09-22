package com.blockevidence.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.model.CaseMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseMemberRepository extends JpaRepository<CaseMember, UUID> {

    List<CaseMember> findByCaseIdOrderByAddedAtAsc(UUID caseId);

    Optional<CaseMember> findByCaseIdAndUserId(UUID caseId, UUID userId);
}
