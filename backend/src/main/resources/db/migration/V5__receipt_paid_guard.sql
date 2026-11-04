-- Defense-in-depth: only a fully settled intent can generate a receipt.
-- Issuance happens in the same transaction as the authenticated tender capture.
CREATE FUNCTION require_paid_intent_for_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM payment_intents p
        WHERE p.id=NEW.payment_intent_id
          AND p.order_id=NEW.order_id
          AND p.aggregate_status='PAID'
          AND p.amount_paid_idr=NEW.total_idr
          AND p.amount_due_idr=NEW.total_idr
    ) THEN
        RAISE EXCEPTION 'Receipt requires a settled matching payment'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER receipt_paid_guard BEFORE INSERT ON receipts
FOR EACH ROW EXECUTE FUNCTION require_paid_intent_for_receipt();
