-- M5: exactly one active, cafe-wide physical till shift (single-cafe MVP).
-- The ledger does NOT allocate waiter-held money to a specific drawer.
CREATE TABLE cash_drawer_shifts (
  id uuid PRIMARY KEY,
  opened_by uuid NOT NULL REFERENCES staff_users(id),
  opening_float_idr bigint NOT NULL CHECK (opening_float_idr BETWEEN 0 AND 1000000000000),
  open_key varchar(128) NOT NULL UNIQUE CHECK (open_key ~ '^[A-Za-z0-9_-]{8,128}$'),
  opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  closed_at timestamptz,
  closed_by uuid REFERENCES staff_users(id),
  close_key varchar(128) CHECK (close_key IS NULL OR close_key ~ '^[A-Za-z0-9_-]{8,128}$'),
  counted_cash_idr bigint CHECK (counted_cash_idr BETWEEN 0 AND 1000000000000),
  recorded_cash_idr bigint CHECK (recorded_cash_idr BETWEEN 0 AND 1000000000000),
  expected_drawer_idr bigint CHECK (expected_drawer_idr BETWEEN 0 AND 1000000000000),
  difference_idr bigint,
  closing_reason varchar(300),
  CONSTRAINT shift_close_all_or_none CHECK (
    (closed_at IS NULL AND closed_by IS NULL AND close_key IS NULL
     AND counted_cash_idr IS NULL AND recorded_cash_idr IS NULL
     AND expected_drawer_idr IS NULL AND difference_idr IS NULL AND closing_reason IS NULL)
    OR
    (closed_at IS NOT NULL AND closed_at >= opened_at AND closed_by IS NOT NULL
     AND close_key IS NOT NULL AND counted_cash_idr IS NOT NULL AND recorded_cash_idr IS NOT NULL
     AND expected_drawer_idr IS NOT NULL AND difference_idr IS NOT NULL AND closing_reason IS NOT NULL)
  ),
  CONSTRAINT shift_expected_matches_cash CHECK (
    expected_drawer_idr IS NULL OR expected_drawer_idr = opening_float_idr + recorded_cash_idr
  ),
  CONSTRAINT shift_difference_matches_count CHECK (
    difference_idr IS NULL OR difference_idr = counted_cash_idr - expected_drawer_idr
  )
);
CREATE UNIQUE INDEX uq_one_active_cash_drawer_shift
  ON cash_drawer_shifts ((true)) WHERE closed_at IS NULL;
CREATE INDEX idx_cash_drawer_shifts_recent ON cash_drawer_shifts(opened_at DESC,id DESC);
CREATE INDEX idx_cash_tenders_recorded_at ON cash_tenders(recorded_at);

CREATE FUNCTION protect_closed_cash_drawer_shift() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR OLD.closed_at IS NOT NULL THEN
    RAISE EXCEPTION 'Completed cash drawer shift evidence is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER cash_drawer_shift_protection
BEFORE UPDATE OR DELETE ON cash_drawer_shifts
FOR EACH ROW EXECUTE FUNCTION protect_closed_cash_drawer_shift();
