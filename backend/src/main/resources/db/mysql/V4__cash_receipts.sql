-- MySQL 8 V4. Receipt snapshots and financial immutability are database-enforced.
-- Not yet an operational POS: later migrations V6-V31 still require porting.
CREATE TABLE receipts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT (UUID()) PRIMARY KEY,
  payment_intent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  receipt_number BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
  total_idr BIGINT NOT NULL CHECK (total_idr > 0),
  currency CHAR(3) COLLATE utf8mb4_bin NOT NULL DEFAULT 'IDR' CHECK (currency='IDR'),
  issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  document_json JSON NOT NULL,
  FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id),
  FOREIGN KEY (order_id) REFERENCES orders(id)
);

DELIMITER //
CREATE TRIGGER receipt_no_update BEFORE UPDATE ON receipts
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Issued receipts are immutable';
END//
CREATE TRIGGER receipt_no_delete BEFORE DELETE ON receipts
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Issued receipts are immutable';
END//
CREATE TRIGGER order_snapshot_no_update BEFORE UPDATE ON orders
FOR EACH ROW
BEGIN
  IF NOT (OLD.visit_id <=> NEW.visit_id)
    OR NOT (OLD.guest_id <=> NEW.guest_id)
    OR NOT (OLD.subtotal_idr <=> NEW.subtotal_idr)
    OR NOT (OLD.discount_idr <=> NEW.discount_idr)
    OR NOT (OLD.tax_idr <=> NEW.tax_idr)
    OR NOT (OLD.service_fee_idr <=> NEW.service_fee_idr)
    OR NOT (OLD.total_idr <=> NEW.total_idr)
    OR NOT (OLD.currency <=> NEW.currency)
    OR NOT (OLD.pricing_policy_version <=> NEW.pricing_policy_version)
  THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Order financial snapshots are immutable';
  END IF;
END//
DELIMITER ;
