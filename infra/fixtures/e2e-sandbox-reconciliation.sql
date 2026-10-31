-- Synthetic quarantined claim for isolated sandbox Chromium admin UI only.
-- This reference intentionally has NO corresponding attempt in the disposable DB.
-- It is not a live webhook and cannot settle, print or release an order.
INSERT INTO sandbox_payment_event_inbox (
  provider_key,provider_event_id,provider_reference,payload_fingerprint,
  method,observed_state,reported_amount_idr,reported_currency,
  reported_environment,classification
) VALUES (
  'SANDBOX_FAKE','e2e_unknown_claim_2026',
  'FAKE_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  repeat('0',64),'QRIS','SUCCEEDED',12000,'IDR',
  'SANDBOX_FAKE_NOT_MONEY','UNKNOWN_REFERENCE'
);
