-- Revocable bearer sessions; never persist plaintext session tokens.
CREATE TABLE staff_sessions (
  token_hash char(64) PRIMARY KEY,
  staff_user_id uuid NOT NULL REFERENCES staff_users(id),
  issued_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CONSTRAINT staff_session_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX idx_staff_sessions_user ON staff_sessions(staff_user_id, expires_at DESC);
CREATE UNIQUE INDEX uq_staff_login_case_insensitive ON staff_users(lower(login));
CREATE TABLE staff_login_throttles (
  login_hash char(64) PRIMARY KEY,
  window_started_at timestamptz NOT NULL DEFAULT now(),
  failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until timestamptz
);
