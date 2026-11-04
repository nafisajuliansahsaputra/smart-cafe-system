-- Dedicated per-account throttle for authenticated current-password verification.
-- The staff_users row lock serializes rotation with login/session issuance.
CREATE TABLE staff_password_change_throttles (
    staff_user_id uuid PRIMARY KEY REFERENCES staff_users(id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL DEFAULT now(),
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until timestamptz
);
