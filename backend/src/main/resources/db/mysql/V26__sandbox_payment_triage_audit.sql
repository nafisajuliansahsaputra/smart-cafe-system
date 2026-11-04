-- MySQL 8: immutable fake-payment investigation notes.
-- These never transition orders, receipts or payment intents to PAID.
CREATE TABLE sandbox_payment_triage_actions (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action_code VARCHAR(40) NOT NULL
    CHECK (action_code IN ('INVESTIGATE','REQUEST_PROVIDER_REVIEW','ESCALATE')),
  idempotency_key VARCHAR(128) NOT NULL
    CHECK (REGEXP_LIKE(idempotency_key,'^[A-Za-z0-9_-]{8,128}$')),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_sandbox_triage_evidence_key UNIQUE (evidence_id,idempotency_key),
  FOREIGN KEY (evidence_id) REFERENCES sandbox_payment_event_inbox(id),
  FOREIGN KEY (actor_staff_id) REFERENCES staff_users(id)
);
CREATE INDEX idx_sandbox_triage_history
  ON sandbox_payment_triage_actions(evidence_id,created_at DESC,id DESC);

DELIMITER //
CREATE TRIGGER sandbox_triage_no_update
  BEFORE UPDATE ON sandbox_payment_triage_actions FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sandbox triage audit is append-only';
END//
CREATE TRIGGER sandbox_triage_no_delete
  BEFORE DELETE ON sandbox_payment_triage_actions FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sandbox triage audit is append-only';
END//
DELIMITER ;
