-- M5: append-only manager investigation for non-zero CLOSED cash shifts.
-- No update to recorded cash, receipts, payments, tenders, or drawer snapshots.
CREATE TABLE cash_variance_review_actions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  shift_id uuid NOT NULL REFERENCES cash_drawer_shifts(id),
  actor_staff_id uuid NOT NULL REFERENCES staff_users(id),
  action_code varchar(40) NOT NULL
    CHECK (action_code IN ('REQUEST_RECOUNT','DOCUMENT_EVIDENCE','ESCALATE')),
  note varchar(300) NOT NULL
    CHECK (char_length(btrim(note)) BETWEEN 10 AND 300),
  idempotency_key varchar(128) NOT NULL
    CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,128}$'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_cash_variance_shift_key UNIQUE (shift_id,idempotency_key)
);
CREATE INDEX idx_cash_variance_history
  ON cash_variance_review_actions(shift_id,created_at DESC,id DESC);

CREATE FUNCTION deny_cash_variance_review_changes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Cash variance investigation history is append-only' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER cash_variance_review_immutable
  BEFORE UPDATE OR DELETE ON cash_variance_review_actions
  FOR EACH ROW EXECUTE FUNCTION deny_cash_variance_review_changes();
