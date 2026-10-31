-- Append-only administrative INVESTIGATION notes for fake event evidence only.
-- No payment intent, attempt, receipt, order, or kitchen mutation is possible.
-- Controlled action codes intentionally exclude PAID, VERIFIED, APPROVED,
-- REFUNDED and RESOLVED to prevent a synthetic claim appearing authoritative.
CREATE TABLE sandbox_payment_triage_actions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  evidence_id uuid NOT NULL REFERENCES sandbox_payment_event_inbox(id),
  actor_staff_id uuid NOT NULL REFERENCES staff_users(id),
  action_code varchar(40) NOT NULL
    CHECK (action_code IN ('INVESTIGATE','REQUEST_PROVIDER_REVIEW','ESCALATE')),
  idempotency_key varchar(128) NOT NULL
    CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,128}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_sandbox_triage_evidence_key
    UNIQUE (evidence_id,idempotency_key)
);
CREATE INDEX idx_sandbox_triage_history
  ON sandbox_payment_triage_actions(evidence_id,created_at DESC,id DESC);

CREATE FUNCTION deny_sandbox_triage_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Sandbox triage audit is append-only' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER sandbox_triage_no_update
  BEFORE UPDATE OR DELETE ON sandbox_payment_triage_actions
  FOR EACH ROW EXECUTE FUNCTION deny_sandbox_triage_mutation();
