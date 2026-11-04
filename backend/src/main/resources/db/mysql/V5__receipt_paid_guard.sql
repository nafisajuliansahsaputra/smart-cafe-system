-- MySQL 8 V5. An unsettled/mismatched payment may never issue a receipt.
-- This is required financial safety behavior, not a best-effort API check.
DELIMITER //
CREATE TRIGGER receipt_paid_guard BEFORE INSERT ON receipts
FOR EACH ROW
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM payment_intents p
    WHERE p.id = NEW.payment_intent_id
      AND p.order_id = NEW.order_id
      AND p.aggregate_status = 'PAID'
      AND p.amount_paid_idr = NEW.total_idr
      AND p.amount_due_idr = NEW.total_idr
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Receipt requires a settled matching payment';
  END IF;
END//
DELIMITER ;
