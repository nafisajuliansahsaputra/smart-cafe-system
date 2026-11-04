-- M3 migration: durable digital attempt metadata ONLY. No payment creation,
-- fake settlement, webhook, credential, or transition handler is activated.
CREATE TABLE digital_payment_attempts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_intent_id uuid NOT NULL REFERENCES payment_intents(id),
  method varchar(20) NOT NULL CHECK (method IN ('QRIS','VA')),
  provider_key varchar(40) NOT NULL,
  provider_reference varchar(128) NOT NULL,
  idempotency_key varchar(128) NOT NULL
    CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,128}$'),
  amount_idr bigint NOT NULL CHECK (amount_idr > 0),
  currency char(3) NOT NULL DEFAULT 'IDR' CHECK (currency='IDR'),
  state varchar(20) NOT NULL DEFAULT 'CREATED'
    CHECK (state IN ('CREATED','PENDING','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT digital_attempt_expiry CHECK (expires_at > created_at),
  CONSTRAINT digital_attempt_provider_reference_unique
    UNIQUE (provider_key,provider_reference),
  CONSTRAINT digital_attempt_idempotency_unique
    UNIQUE (payment_intent_id,idempotency_key)
);

-- Only one unresolved digital charge per intent. A failure/expiry may be
-- retried with a NEW key; genuine late payment requires reconciliation.
CREATE UNIQUE INDEX uq_unresolved_digital_attempt_per_intent
  ON digital_payment_attempts(payment_intent_id)
  WHERE state IN ('CREATED','PENDING');
CREATE INDEX idx_digital_attempt_status_expiry
  ON digital_payment_attempts(state,expires_at)
  WHERE state IN ('CREATED','PENDING');

-- Even direct DB writes cannot attach a digital attempt to a cash intent or
-- another amount/method. The authoritative intent must already be selected
-- for QRIS/VA by a separately reviewed checkout/domain workflow.
CREATE FUNCTION validate_digital_payment_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i payment_intents%ROWTYPE;
BEGIN
  SELECT * INTO i FROM payment_intents WHERE id=NEW.payment_intent_id FOR SHARE;
  IF NOT FOUND OR i.method_selected NOT IN ('QRIS','VA') OR
     NEW.method IS DISTINCT FROM i.method_selected OR
     NEW.amount_idr IS DISTINCT FROM i.amount_due_idr OR
     NEW.currency IS DISTINCT FROM i.currency THEN
    RAISE EXCEPTION 'Digital attempt must match authorized payment intent' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF (OLD.payment_intent_id,OLD.method,OLD.provider_key,
        OLD.provider_reference,OLD.idempotency_key,OLD.amount_idr,OLD.currency,
        OLD.created_at,OLD.expires_at)
       IS DISTINCT FROM
       (NEW.payment_intent_id,NEW.method,NEW.provider_key,
        NEW.provider_reference,NEW.idempotency_key,NEW.amount_idr,NEW.currency,
        NEW.created_at,NEW.expires_at) THEN
      RAISE EXCEPTION 'Digital attempt identity and financial snapshot immutable'
        USING ERRCODE='23514';
    END IF;
    IF OLD.state<>NEW.state AND NOT (
       (OLD.state='CREATED' AND NEW.state IN ('PENDING','FAILED','EXPIRED','CANCELLED'))
       OR (OLD.state='PENDING' AND NEW.state IN ('SUCCEEDED','FAILED','EXPIRED','CANCELLED'))
    ) THEN
      RAISE EXCEPTION 'Illegal digital payment attempt status transition'
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER digital_payment_attempt_validate
  BEFORE INSERT OR UPDATE ON digital_payment_attempts
  FOR EACH ROW EXECUTE FUNCTION validate_digital_payment_attempt();
