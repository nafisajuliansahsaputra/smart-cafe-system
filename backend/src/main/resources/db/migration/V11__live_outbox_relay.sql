-- Separate durable live-publication bookkeeping from outbox.processed_at.
-- processed_at belongs to future business/external integration consumers and
-- must NEVER be set simply because a UI update was published.
ALTER TABLE outbox_events
    ADD COLUMN live_published_at timestamptz;

CREATE INDEX idx_outbox_live_pending
    ON outbox_events(created_at, id)
    WHERE aggregate_type = 'ORDER' AND live_published_at IS NULL;

-- No guest PII, token, receipt contents, or financial amounts in this table.
-- Sequence is allocated by a single transaction-advisory-locked publisher,
-- so completed relay commits cannot overtake a lower sequence.
CREATE TABLE live_order_signals (
    sequence_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outbox_id uuid NOT NULL UNIQUE REFERENCES outbox_events(id),
    order_id uuid NOT NULL REFERENCES orders(id),
    event_type varchar(120) NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_live_order_signals_order ON live_order_signals(order_id, sequence_id);
