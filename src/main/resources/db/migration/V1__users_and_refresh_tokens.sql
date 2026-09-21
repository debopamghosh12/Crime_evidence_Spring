-- Phase 1 (A1/A3): users and refresh tokens. Flyway owns the schema; Hibernate only validates it.
-- "users" not "user": USER is a reserved word in PostgreSQL.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    department    VARCHAR(200),
    role          VARCHAR(30)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    -- Keep in step with the Role enum. Renaming a constant needs a new migration.
    CONSTRAINT ck_users_role CHECK (role IN
        ('COLLECTOR', 'FORENSIC_ANALYST', 'PROSECUTOR', 'JUDGE', 'AUDITOR', 'ADMIN'))
);

-- Emails are unique ignoring case; UserRepository.findByEmailIgnoreCase relies on this.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE TABLE refresh_tokens (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id),
    -- SHA-256 hex of the opaque token. The token itself is never stored.
    token_hash VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
