-- G3 (docs/G3_SYNC_DESIGN.md sections 5, 7): the off-chain read model synced from chaincode events. A NEW
-- migration; V1-V3 are never edited. The ledger stays the source of truth (C-01 spirit); everything here can
-- always be rebuilt by resetting ledger_sync_checkpoint and replaying the ledger from block 0.

CREATE TABLE ledger_sync_checkpoint (
    id           SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),   -- exactly one row, ever
    block_number BIGINT,
    tx_id        VARCHAR(64),
    updated_at   TIMESTAMPTZ  NOT NULL
);
-- Starts empty (block_number/tx_id NULL): the listener's first run replays the whole ledger from block 0.
INSERT INTO ledger_sync_checkpoint (id, updated_at) VALUES (1, now());

-- Idempotency log AND the H3 activity feed source in one table (design section 5): the UNIQUE constraint on
-- tx_id is what makes re-applying a redelivered event a guaranteed no-op.
CREATE TABLE evidence_activity (
    id            UUID          PRIMARY KEY,
    tx_id         VARCHAR(64)   NOT NULL UNIQUE,
    block_number  BIGINT        NOT NULL,
    evidence_id   VARCHAR(50)   NOT NULL,
    version       INT           NOT NULL,
    action        VARCHAR(30)   NOT NULL,
    case_id       VARCHAR(64),
    status        VARCHAR(10),
    actor_id      VARCHAR(36),
    actor_role    VARCHAR(20),
    reason        VARCHAR(2000),
    ledger_at     TIMESTAMPTZ   NOT NULL,   -- the ledger's own transaction timestamp (C4), not this server's clock
    processed_at  TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_activity_evidence ON evidence_activity (evidence_id, version);
CREATE INDEX ix_activity_time     ON evidence_activity (ledger_at DESC);

-- Current state, for H1 search/filter and H2 aggregates. Overwritten on every event for that item (with a
-- compare-and-set guard against ever regressing the version, see EvidenceProjectionRepository.upsert).
CREATE TABLE evidence_projection (
    evidence_id        VARCHAR(50)  PRIMARY KEY,
    case_id             VARCHAR(64),
    evidence_type        VARCHAR(10)  NOT NULL,
    status               VARCHAR(10)  NOT NULL,
    version              INT          NOT NULL,
    current_custodian    VARCHAR(36),
    created_by           VARCHAR(36)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    last_action          VARCHAR(30)  NOT NULL,
    last_reason          VARCHAR(2000)
);
CREATE INDEX ix_projection_case   ON evidence_projection (case_id);
CREATE INDEX ix_projection_status ON evidence_projection (status);
