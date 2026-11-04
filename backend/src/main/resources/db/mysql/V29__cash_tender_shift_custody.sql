-- MySQL 8: cash tender attribution and immutable waiter-to-drawer handoff.
ALTER TABLE cash_tenders
  ADD COLUMN cash_shift_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  ADD CONSTRAINT fk_cash_tender_shift
    FOREIGN KEY (cash_shift_id) REFERENCES cash_drawer_shifts(id);
CREATE INDEX idx_cash_tenders_shift ON cash_tenders(cash_shift_id);

CREATE TABLE cash_tender_handoffs (
  tender_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  cash_shift_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  waiter_staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  received_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  received_at DATETIME(6),
  acknowledgment_key VARCHAR(128),
  CONSTRAINT handoff_confirmation_complete CHECK (
    (received_at IS NULL AND received_by IS NULL AND acknowledgment_key IS NULL)
    OR (received_at IS NOT NULL AND received_by IS NOT NULL
      AND acknowledgment_key IS NOT NULL
      AND REGEXP_LIKE(acknowledgment_key,'^[A-Za-z0-9_-]{8,128}$'))),
  CONSTRAINT handoff_unique_ack_key UNIQUE (cash_shift_id,acknowledgment_key),
  FOREIGN KEY (tender_id) REFERENCES cash_tenders(id),
  FOREIGN KEY (cash_shift_id) REFERENCES cash_drawer_shifts(id),
  FOREIGN KEY (waiter_staff_id) REFERENCES staff_users(id),
  FOREIGN KEY (received_by) REFERENCES staff_users(id)
);
CREATE INDEX idx_pending_cash_handoffs
  ON cash_tender_handoffs(cash_shift_id,received_at,tender_id);

DELIMITER //
CREATE TRIGGER cash_handoff_no_update
  BEFORE UPDATE ON cash_tender_handoffs FOR EACH ROW
BEGIN
  IF OLD.received_at IS NOT NULL
    OR NOT (NEW.tender_id <=> OLD.tender_id)
    OR NOT (NEW.cash_shift_id <=> OLD.cash_shift_id)
    OR NOT (NEW.waiter_staff_id <=> OLD.waiter_staff_id)
  THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Cash handoff evidence cannot be changed';
  END IF;
END//
CREATE TRIGGER cash_handoff_no_delete
  BEFORE DELETE ON cash_tender_handoffs FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='Cash handoff evidence cannot be deleted';
END//
CREATE TRIGGER cash_handoff_matches_tender
  BEFORE INSERT ON cash_tender_handoffs FOR EACH ROW
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM cash_tenders t
    JOIN payment_intents p ON p.id=t.payment_intent_id
    WHERE t.id=NEW.tender_id
      AND t.cash_shift_id=NEW.cash_shift_id
      AND t.staff_user_id=NEW.waiter_staff_id
      AND p.method_selected='CASH'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Cash custody must match an actual shift tender';
  END IF;
END//
DELIMITER ;
