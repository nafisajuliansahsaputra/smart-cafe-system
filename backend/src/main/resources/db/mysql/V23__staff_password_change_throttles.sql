-- MySQL 8: current-password verification uses per-account row locking.
CREATE TABLE staff_password_change_throttles (
  staff_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  window_started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until DATETIME(6),
  FOREIGN KEY (staff_user_id) REFERENCES staff_users(id) ON DELETE CASCADE
);
