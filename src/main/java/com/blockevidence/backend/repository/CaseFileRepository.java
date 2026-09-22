package com.blockevidence.backend.repository;

import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.model.CaseFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseFileRepository extends JpaRepository<CaseFile, UUID> {

    // Case-insensitive to match the unique index on lower(case_number) in V2.
    boolean existsByCaseNumberIgnoreCase(String caseNumber);

    List<CaseFile> findAllByOrderByCreatedAtDesc();
}
