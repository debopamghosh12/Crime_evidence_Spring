-- F2/F3 (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md): per-user RSA keypairs and per-evidence wrapped content keys.
-- Neither table touches the ledger (F4/C-06 does not apply); both are ordinary off-chain Postgres state.

CREATE TABLE user_keys (
    user_id                UUID PRIMARY KEY REFERENCES users(id),
    public_key             BYTEA NOT NULL,
    encrypted_private_key  BYTEA NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL
);

CREATE TABLE evidence_content_keys (
    id            UUID PRIMARY KEY,
    evidence_id   VARCHAR(64) NOT NULL,
    user_id       UUID NOT NULL REFERENCES users(id),
    wrapped_key   BYTEA NOT NULL,
    wrapped_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_evidence_content_keys UNIQUE (evidence_id, user_id)
);

CREATE INDEX ix_evidence_content_keys_evidence ON evidence_content_keys (evidence_id);
