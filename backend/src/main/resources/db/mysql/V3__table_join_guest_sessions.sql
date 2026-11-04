-- MySQL core parity stage 3; retains existing PG migrations unchanged.
-- Join proof is short-lived; static table QR alias is never sufficient to join.
ALTER TABLE table_visits ADD COLUMN join_proof_expires_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

CREATE TABLE guest_sessions (
    token_hash char(64) PRIMARY KEY,
    guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    visit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    FOREIGN KEY (guest_id,visit_id) REFERENCES visit_guests(id,visit_id),
    CONSTRAINT guest_session_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX idx_guest_sessions_guest ON guest_sessions(guest_id,expires_at DESC);
CREATE INDEX idx_guest_sessions_visit ON guest_sessions(visit_id,expires_at DESC);
