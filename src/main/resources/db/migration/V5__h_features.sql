-- Phase 4 (H4, A6): notifications and the access audit log. A NEW migration; V1-V4 are never edited.
-- H1-H3 need no new tables: they read V4's evidence_projection/evidence_activity directly.

CREATE TABLE notifications (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES users (id),
    type         VARCHAR(30)   NOT NULL,
    evidence_id  VARCHAR(50),
    case_id      VARCHAR(64),
    message      VARCHAR(500)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    read_at      TIMESTAMPTZ
);
CREATE INDEX ix_notifications_user ON notifications (user_id, read_at, created_at DESC);

-- A6: user is nullable (an AUTH_FAILED entry for a bad/missing token may have no verifiable principal at all).
CREATE TABLE audit_log (
    id          UUID          PRIMARY KEY,
    user_id     UUID,
    email       VARCHAR(255),
    action      VARCHAR(40)   NOT NULL,
    resource    VARCHAR(200),
    ip_address  VARCHAR(64),
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_audit_log_user   ON audit_log (user_id, occurred_at DESC);
CREATE INDEX ix_audit_log_action ON audit_log (action, occurred_at DESC);
