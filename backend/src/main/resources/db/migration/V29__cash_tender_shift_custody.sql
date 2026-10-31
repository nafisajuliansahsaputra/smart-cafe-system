-- M5: assign each cash tender to its active drawer shift and preserve physical custody.
-- Legacy tenders before the first shift are explicitly unassigned and excluded.
ALTER TABLE cash_tenders ADD COLUMN cash_shift_id uuid REFERENCES cash_drawer_shifts(id);
CREATE INDEX idx_cash_tenders_shift ON cash_tenders(cash_shift_id)
  WHERE cash_shift_id IS NOT NULL;

CREATE TABLE cash_tender_handoffs (
  tender_id uuid PRIMARY KEY REFERENCES cash_tenders(id),
  cash_shift_id uuid NOT NULL REFERENCES cash_drawer_shifts(id),
  waiter_staff_id uuid NOT NULL REFERENCES staff_users(id),
  received_by uuid REFERENCES staff_users(id),
  received_at timestamptz,
  acknowledgment_key varchar(128),
  CONSTRAINT handoff_confirmation_complete CHECK (
    (received_at IS NULL AND received_by IS NULL AND acknowledgment_key IS NULL)
    OR (received_at IS NOT NULL AND received_by IS NOT NULL AND
        acknowledgment_key ~ '^[A-Za-z0-9_-]{8,128}$')
  ),
  CONSTRAINT handoff_unique_ack_key UNIQUE (cash_shift_id, acknowledgment_key)
);
CREATE INDEX idx_pending_cash_handoffs
  ON cash_tender_handoffs(cash_shift_id,tender_id) WHERE received_at IS NULL;

CREATE FUNCTION deny_confirmed_cash_handoff_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR OLD.received_at IS NOT NULL OR
     (NEW.tender_id,NEW.cash_shift_id,NEW.waiter_staff_id)
       IS DISTINCT FROM (OLD.tender_id,OLD.cash_shift_id,OLD.waiter_staff_id) THEN
    RAISE EXCEPTION 'Cash handoff evidence cannot be changed' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER cash_handoff_no_mutation
  BEFORE UPDATE OR DELETE ON cash_tender_handoffs
  FOR EACH ROW EXECUTE FUNCTION deny_confirmed_cash_handoff_mutation();
