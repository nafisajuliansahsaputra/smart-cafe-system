-- MySQL 8: a guest return request is not a physical return.
-- Update copy locks to include RETURN_PENDING, and preserve loan history.
ALTER TABLE physical_game_loans ADD COLUMN return_requested_at DATETIME(6);
ALTER TABLE physical_game_loans
  DROP CHECK game_loan_status,
  DROP CHECK loan_copy_requires_assignment,
  DROP CHECK loan_handover_requires_time;
ALTER TABLE physical_game_loans
  ADD CONSTRAINT game_loan_status CHECK (
    status IN ('REQUESTED','ASSIGNED','IN_USE',
               'RETURN_PENDING','RETURNED','CANCELLED')),
  ADD CONSTRAINT loan_copy_requires_assignment CHECK (
    (status IN ('REQUESTED','CANCELLED') AND copy_id IS NULL)
    OR (status IN ('ASSIGNED','IN_USE','RETURN_PENDING','RETURNED')
        AND copy_id IS NOT NULL)),
  ADD CONSTRAINT loan_handover_requires_time CHECK (
    status NOT IN ('IN_USE','RETURN_PENDING','RETURNED')
    OR handed_over_at IS NOT NULL),
  ADD CONSTRAINT loan_return_request_requires_time CHECK (
    status <> 'RETURN_PENDING' OR return_requested_at IS NOT NULL);

-- MySQL generates NULL active keys for terminal statuses so a returned
-- physical copy may be lent again. Rebuild generated indexes with new states.
ALTER TABLE physical_game_loans
  DROP INDEX uq_active_game_copy_loan,
  DROP INDEX uq_active_game_request_visit;
ALTER TABLE physical_game_loans
  DROP COLUMN active_copy_key,
  DROP COLUMN active_request_key;
ALTER TABLE physical_game_loans
  ADD COLUMN active_copy_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
      CASE WHEN status IN ('ASSIGNED','IN_USE','RETURN_PENDING')
        THEN copy_id ELSE NULL END
    ) STORED,
  ADD COLUMN active_request_key CHAR(73) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
      CASE WHEN status IN ('REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING')
        THEN CONCAT(visit_id,':',game_id) ELSE NULL END
    ) STORED;
CREATE UNIQUE INDEX uq_active_game_copy_loan
  ON physical_game_loans(active_copy_key);
CREATE UNIQUE INDEX uq_active_game_request_visit
  ON physical_game_loans(active_request_key);

ALTER TABLE live_game_signals DROP CHECK live_game_signal_type;
ALTER TABLE live_game_signals
  ADD CONSTRAINT live_game_signal_type CHECK (event_type IN (
    'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED','GAME_LOAN_HANDED_OVER',
    'GAME_LOAN_RETURN_REQUESTED','GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED'));
