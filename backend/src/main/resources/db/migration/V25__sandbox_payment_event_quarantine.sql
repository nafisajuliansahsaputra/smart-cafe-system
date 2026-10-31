-- M3: isolated, immutable SANDBOX event observations only.
-- This is NOT an authenticated provider callback receiver. It cannot settle an
-- order or create a receipt; every record is quarantined for future reconciliation.
CREATE TABLE sandbox_payment_event_inbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_key varchar(40) NOT NULL DEFAULT 'SANDBOX_FAKE'
    CHECK (provider_key = 'SANDBOX_FAKE'),
  provider_event_id varchar(128) NOT NULL
    CHECK (provider_event_id ~ '^[A-Za-z0-9_-]{8,128}$'),
  provider_reference varchar(128) NOT NULL
    CHECK (provider_reference ~ '^FAKE_[a-f0-9]{32}$'),
  payload_fingerprint char(64) NOT NULL
    CHECK (payload_fingerprint ~ '^[a-f0-9]{64}$'),
  method varchar(20) NOT NULL CHECK (method IN ('QRIS','VA')),
  observed_state varchar(20) NOT NULL
    CHECK (observed_state IN ('PENDING','SUCCEEDED','FAILED','EXPIRED')),
  reported_amount_idr bigint NOT NULL CHECK (reported_amount_idr > 0),
  reported_currency char(3) NOT NULL
    CHECK (reported_currency ~ '^[A-Z]{3}$'),
  reported_environment varchar(60) NOT NULL
    CHECK (reported_environment ~ '^[A-Z_]{4,60}$'),
  attempt_id uuid REFERENCES digital_payment_attempts(id),
  classification varchar(32) NOT NULL
    CHECK (classification IN ('QUARANTINED','UNKNOWN_REFERENCE',
       'FINANCIAL_MISMATCH','ENVIRONMENT_MISMATCH','LATE_SUCCESS',
       'CONTRADICTORY_STATE','ALREADY_SETTLED')),
  received_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sandbox_event_identity_unique UNIQUE (provider_key,provider_event_id)
);
CREATE INDEX idx_sandbox_event_reconciliation
  ON sandbox_payment_event_inbox(classification,received_at,id);
CREATE INDEX idx_sandbox_event_attempt
  ON sandbox_payment_event_inbox(attempt_id,received_at DESC)
  WHERE attempt_id IS NOT NULL;

CREATE FUNCTION deny_sandbox_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Sandbox payment event evidence is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER sandbox_event_no_update BEFORE UPDATE OR DELETE
  ON sandbox_payment_event_inbox
  FOR EACH ROW EXECUTE FUNCTION deny_sandbox_event_mutation();

-- No triggers or code attach the inbox to paid aggregates/receipt issuance.
