-- Phase 3 (E1, E2): cases and their team members. Off-chain by design (C-06): the ledger keeps only the case NUMBER as a plain
-- string on each evidence record; everything descriptive lives here. A NEW migration: V1 is never edited.

CREATE TABLE cases (
    id              UUID          PRIMARY KEY,
    -- The same character set the ledger accepts for an evidence caseId, so a case number can always be used as one (E3).
    case_number     VARCHAR(64)   NOT NULL,
    title           VARCHAR(200)  NOT NULL,
    description     VARCHAR(2000),
    lead_officer_id UUID          NOT NULL REFERENCES users (id),
    status          VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    created_by      UUID          NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_cases_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_cases_number_lower ON cases (lower(case_number));

CREATE TABLE case_members (
    id        UUID         PRIMARY KEY,
    case_id   UUID         NOT NULL REFERENCES cases (id),
    user_id   UUID         NOT NULL REFERENCES users (id),
    -- The user's role ON THIS CASE, distinct from their system role.
    case_role VARCHAR(30)  NOT NULL,
    added_by  UUID         NOT NULL REFERENCES users (id),
    added_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_case_members UNIQUE (case_id, user_id),
    CONSTRAINT ck_case_members_role CHECK (case_role IN
        ('LEAD_OFFICER', 'INVESTIGATOR', 'FORENSIC_ANALYST', 'PROSECUTOR', 'OBSERVER'))
);

CREATE INDEX ix_case_members_user ON case_members (user_id);
