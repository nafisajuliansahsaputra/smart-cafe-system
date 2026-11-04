-- MySQL 8: manager-reviewable physical loss, without automatic customer charges.
ALTER TABLE physical_game_loans
  ADD COLUMN lost_at DATETIME(6),
  ADD COLUMN lost_confirmed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  ADD CONSTRAINT fk_game_loan_lost_manager FOREIGN KEY (lost_confirmed_by) REFERENCES staff_users(id);
ALTER TABLE physical_game_loans
  DROP CHECK game_loan_status,
  DROP CHECK loan_copy_requires_assignment;
ALTER TABLE physical_game_loans
  ADD CONSTRAINT game_loan_status CHECK (status IN (
    'REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING','RETURNED','CANCELLED','LOST')),
  ADD CONSTRAINT loan_copy_requires_assignment CHECK (
    (status IN ('REQUESTED','CANCELLED') AND copy_id IS NULL)
    OR (status IN ('ASSIGNED','IN_USE','RETURN_PENDING','RETURNED','LOST')
        AND copy_id IS NOT NULL)),
  ADD CONSTRAINT loan_loss_requires_manager CHECK (
    status <> 'LOST' OR (lost_at IS NOT NULL AND lost_confirmed_by IS NOT NULL));

CREATE TABLE physical_game_loss_incidents (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  loan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reported_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(300) NOT NULL CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 5 AND 300),
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN'
    CHECK (status IN ('OPEN','DISMISSED','CONFIRMED_LOST')),
  reported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reviewed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  reviewed_at DATETIME(6),
  review_reason VARCHAR(300),
  open_loan_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN loan_id ELSE NULL END) STORED,
  UNIQUE KEY uq_open_physical_game_loss (open_loan_key),
  CONSTRAINT game_loss_review_complete CHECK (
    (status='OPEN' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_reason IS NULL)
    OR (status IN ('DISMISSED','CONFIRMED_LOST')
      AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
      AND CHAR_LENGTH(TRIM(review_reason)) BETWEEN 5 AND 300)),
  FOREIGN KEY (loan_id) REFERENCES physical_game_loans(id),
  FOREIGN KEY (reported_by) REFERENCES staff_users(id),
  FOREIGN KEY (reviewed_by) REFERENCES staff_users(id)
);
CREATE INDEX idx_physical_game_loss_review
  ON physical_game_loss_incidents(status,reported_at DESC,id DESC);

ALTER TABLE live_game_signals DROP CHECK live_game_signal_type;
ALTER TABLE live_game_signals
  ADD CONSTRAINT live_game_signal_type CHECK (
    (loan_id IS NOT NULL AND event_type IN (
      'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED','GAME_LOAN_HANDED_OVER',
      'GAME_LOAN_RETURN_REQUESTED','GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED',
      'GAME_LOSS_REPORTED','GAME_LOSS_DISMISSED','GAME_LOSS_CONFIRMED'))
    OR (game_id IS NOT NULL AND event_type='GAME_INVENTORY_CHANGED'));
