package com.blockevidence.backend.domain;

/** E2: a person's part in ONE case. Separate from their system role (security.Role). */
public enum CaseRole {
    LEAD_OFFICER,
    INVESTIGATOR,
    FORENSIC_ANALYST,
    PROSECUTOR,
    OBSERVER
}
