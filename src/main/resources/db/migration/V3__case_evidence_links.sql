-- Phase 3 (E3): links a ledger evidence item to a real case row. The ledger itself still only ever sees the
-- case NUMBER as a plain string (C-06, unchanged); this table is what makes that string a foreign key to a
-- real case, off-chain, the same treatment E1/E2 already give cases. A NEW migration: V1 and V2 are never edited.
-- One evidence item names exactly one case, matching the ledger record's own single caseId field.
CREATE TABLE case_evidence (
    id          UUID         PRIMARY KEY,
    case_id     UUID         NOT NULL REFERENCES cases (id),
    -- The ledger's evidenceId ("EV-<uuid>"), not a foreign key (the evidence itself lives on the ledger, not here).
    evidence_id VARCHAR(50)  NOT NULL UNIQUE,
    linked_by   UUID         NOT NULL REFERENCES users (id),
    linked_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_case_evidence_case ON case_evidence (case_id);
