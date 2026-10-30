-- Persist a per-staff throttle for authenticated password-change attempts.
CREATE TABLE staff_password_change_throttles (
    staff_user_id uuid PRIMARY KEY REFERENCES staff_users(id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL DEFAULT now(),
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until timestamptz
);
