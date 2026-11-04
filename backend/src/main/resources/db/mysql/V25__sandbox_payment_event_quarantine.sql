-- MySQL 8: fake webhook inbox is immutable evidence, never settles money.
CREATE TABLE sandbox_payment_event_inbox (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  provider_key VARCHAR(40) COLLATE utf8mb4_bin NOT NULL DEFAULT 'SANDBOX_FAKE'
    CHECK (provider_key='SANDBOX_FAKE'),
  provider_event_id VARCHAR(128) NOT NULL
    CHECK (REGEXP_LIKE(provider_event_id,'^[A-Za-z0-9_-]{8,128}$')),
  provider_reference VARCHAR(128) NOT NULL
    CHECK (REGEXP_LIKE(provider_reference,'^FAKE_[a-f0-9]{32}$','c')),
  payload_fingerprint CHAR(64) NOT NULL
    CHECK (REGEXP_LIKE(payload_fingerprint,'^[a-f0-9]{64}$','c')),
  method VARCHAR(20) COLLATE utf8mb4_bin NOT NULL CHECK (method IN ('QRIS','VA')),
  observed_state VARCHAR(20) COLLATE utf8mb4_bin NOT NULL
    CHECK (observed_state IN ('PENDING','SUCCEEDED','FAILED','EXPIRED')),
  reported_amount_idr BIGINT NOT NULL CHECK (reported_amount_idr > 0),
  reported_currency CHAR(3) COLLATE utf8mb4_bin NOT NULL
    CHECK (REGEXP_LIKE(reported_currency,'^[A-Z]{3}$','c')),
  reported_environment VARCHAR(60) COLLATE utf8mb4_bin NOT NULL
    CHECK (REGEXP_LIKE(reported_environment,'^[A-Z_]{4,60}$','c')),
  attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  classification VARCHAR(32) COLLATE utf8mb4_bin NOT NULL
    CHECK (classification IN (
      'QUARANTINED','UNKNOWN_REFERENCE','FINANCIAL_MISMATCH',
      'ENVIRONMENT_MISMATCH','LATE_SUCCESS','CONTRADICTORY_STATE','ALREADY_SETTLED')),
  received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT sandbox_event_identity_unique UNIQUE (provider_key,provider_event_id),
  FOREIGN KEY (attempt_id) REFERENCES digital_payment_attempts(id)
);
CREATE INDEX idx_sandbox_event_reconciliation
  ON sandbox_payment_event_inbox(classification,received_at,id);
CREATE INDEX idx_sandbox_event_attempt
  ON sandbox_payment_event_inbox(attempt_id,received_at DESC);

DELIMITER //
CREATE TRIGGER sandbox_event_no_update
  BEFORE UPDATE ON sandbox_payment_event_inbox FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='Sandbox payment event evidence is immutable';
END//
CREATE TRIGGER sandbox_event_no_delete
  BEFORE DELETE ON sandbox_payment_event_inbox FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='Sandbox payment event evidence is immutable';
END//
DELIMITER ;
