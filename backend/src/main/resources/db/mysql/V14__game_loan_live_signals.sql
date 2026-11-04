-- MySQL 8: physical-unit loan signals use a durable sequence, no guest PII.
-- V11's idx_outbox_live_pending already covers all aggregate types here.
-- Do not duplicate the same four-column outbox index on free-tier storage.
CREATE TABLE live_game_signals (
  sequence_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  loan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  CONSTRAINT live_game_signal_type CHECK (event_type IN (
      'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED','GAME_LOAN_HANDED_OVER',
      'GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED')),
  published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (outbox_id) REFERENCES outbox_events(id),
  FOREIGN KEY (loan_id) REFERENCES physical_game_loans(id)
);
CREATE INDEX idx_live_game_signals_loan ON live_game_signals(loan_id,sequence_id);
