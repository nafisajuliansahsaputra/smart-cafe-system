-- MySQL core parity stage 2; retains existing PG migrations unchanged.
-- Revocable bearer sessions; never persist plaintext session tokens.
CREATE TABLE staff_sessions (
  token_hash char(64) PRIMARY KEY,
  staff_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6),
  CONSTRAINT staff_session_expiry CHECK (expires_at > issued_at),
    FOREIGN KEY (staff_user_id) REFERENCES staff_users(id)
);
CREATE INDEX idx_staff_sessions_user ON staff_sessions(staff_user_id, expires_at DESC);
CREATE UNIQUE INDEX uq_staff_login_case_insensitive ON staff_users ((lower(login)));
CREATE TABLE staff_login_throttles (
  login_hash char(64) PRIMARY KEY,
  window_started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until DATETIME(6)
);
