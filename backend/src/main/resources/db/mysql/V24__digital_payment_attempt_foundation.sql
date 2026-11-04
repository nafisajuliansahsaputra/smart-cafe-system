-- MySQL 8: digital attempts are sandbox metadata, NEVER proof of settlement.
-- All new writes verify the method/amount/currency against locked intent.
CREATE TABLE digital_payment_attempts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  payment_intent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  method VARCHAR(20) COLLATE utf8mb4_bin NOT NULL CHECK (method IN ('QRIS','VA')),
  provider_key VARCHAR(40) NOT NULL,
  provider_reference VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL
    CHECK (REGEXP_LIKE(idempotency_key,'^[A-Za-z0-9_-]{8,128}$')),
  amount_idr BIGINT NOT NULL CHECK (amount_idr > 0),
  currency CHAR(3) COLLATE utf8mb4_bin NOT NULL DEFAULT 'IDR' CHECK (currency='IDR'),
  state VARCHAR(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'CREATED'
    CHECK (state IN ('CREATED','PENDING','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_intent_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
      CASE WHEN state IN ('CREATED','PENDING') THEN payment_intent_id ELSE NULL END
    ) STORED,
  CONSTRAINT digital_attempt_expiry CHECK (expires_at > created_at),
  CONSTRAINT digital_attempt_provider_reference_unique
    UNIQUE (provider_key,provider_reference),
  CONSTRAINT digital_attempt_idempotency_unique
    UNIQUE (payment_intent_id,idempotency_key),
  UNIQUE KEY uq_unresolved_digital_attempt_per_intent (active_intent_key),
  FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id)
);
CREATE INDEX idx_digital_attempt_status_expiry
  ON digital_payment_attempts(state,expires_at);

DELIMITER //
CREATE TRIGGER digital_payment_attempt_validate_insert
  BEFORE INSERT ON digital_payment_attempts FOR EACH ROW
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM payment_intents p
    WHERE p.id=NEW.payment_intent_id
      AND p.method_selected IN ('QRIS','VA')
      AND p.method_selected=NEW.method
      AND p.amount_due_idr=NEW.amount_idr
      AND p.currency=NEW.currency
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Digital attempt must match authorized payment intent';
  END IF;
END//
CREATE TRIGGER digital_payment_attempt_validate_update
  BEFORE UPDATE ON digital_payment_attempts FOR EACH ROW
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM payment_intents p
    WHERE p.id=NEW.payment_intent_id
      AND p.method_selected IN ('QRIS','VA')
      AND p.method_selected=NEW.method
      AND p.amount_due_idr=NEW.amount_idr
      AND p.currency=NEW.currency
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Digital attempt must match authorized payment intent';
  END IF;
  IF NOT (OLD.payment_intent_id <=> NEW.payment_intent_id)
      OR NOT (OLD.method <=> NEW.method)
      OR NOT (OLD.provider_key <=> NEW.provider_key)
      OR NOT (OLD.provider_reference <=> NEW.provider_reference)
      OR NOT (OLD.idempotency_key <=> NEW.idempotency_key)
      OR NOT (OLD.amount_idr <=> NEW.amount_idr)
      OR NOT (OLD.currency <=> NEW.currency)
      OR NOT (OLD.created_at <=> NEW.created_at)
      OR NOT (OLD.expires_at <=> NEW.expires_at)
  THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Digital attempt identity and financial snapshot immutable';
  END IF;
  IF OLD.state <> NEW.state AND NOT (
       (OLD.state='CREATED' AND NEW.state IN ('PENDING','FAILED','EXPIRED','CANCELLED'))
       OR (OLD.state='PENDING' AND NEW.state IN ('SUCCEEDED','FAILED','EXPIRED','CANCELLED'))
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Illegal digital payment attempt status transition';
  END IF;
END//
DELIMITER ;
