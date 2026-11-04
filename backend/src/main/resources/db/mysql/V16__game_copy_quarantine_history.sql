-- MySQL 8: permanently attributed physical condition history.
CREATE TABLE physical_game_copy_condition_events (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  copy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  loan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_condition VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
  after_condition VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
  reason VARCHAR(300) NOT NULL,
  recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT game_condition_before CHECK (before_condition IN ('AVAILABLE','DAMAGED')),
  CONSTRAINT game_condition_after CHECK (after_condition IN ('AVAILABLE','DAMAGED')),
  CONSTRAINT game_condition_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 5 AND 300),
  CONSTRAINT condition_event_transition CHECK (before_condition <> after_condition),
  FOREIGN KEY (copy_id) REFERENCES physical_game_copies(id),
  FOREIGN KEY (loan_id) REFERENCES physical_game_loans(id),
  FOREIGN KEY (actor_id) REFERENCES staff_users(id)
);
CREATE INDEX idx_game_copy_condition_events_copy
  ON physical_game_copy_condition_events(copy_id,recorded_at DESC);
