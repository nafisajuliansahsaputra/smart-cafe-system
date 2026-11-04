-- Join proof is short-lived; static table QR alias is never sufficient to join.
ALTER TABLE table_visits ADD COLUMN join_proof_expires_at timestamptz NOT NULL DEFAULT now();

CREATE TABLE guest_sessions (
    token_hash char(64) PRIMARY KEY,
    guest_id uuid NOT NULL,
    visit_id uuid NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    FOREIGN KEY (guest_id,visit_id) REFERENCES visit_guests(id,visit_id),
    CONSTRAINT guest_session_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX idx_guest_sessions_guest ON guest_sessions(guest_id,expires_at DESC);
CREATE INDEX idx_guest_sessions_visit ON guest_sessions(visit_id,expires_at DESC);
