-- Table visits have their own durable, scoped live projection.
-- Do not add a nullable order FK or expose secret join proof material.
CREATE INDEX idx_outbox_live_table_pending
    ON outbox_events(created_at,id)
    WHERE aggregate_type='TABLE_VISIT' AND live_published_at IS NULL;

CREATE TABLE live_table_signals (
    sequence_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outbox_id uuid NOT NULL UNIQUE REFERENCES outbox_events(id),
    visit_id uuid NOT NULL REFERENCES table_visits(id),
    table_id uuid NOT NULL REFERENCES cafe_tables(id),
    event_type varchar(120) NOT NULL CHECK (
        event_type IN ('TABLE_VISIT_OPENED','TABLE_JOIN_PROOF_ROTATED','TABLE_VISIT_CLOSED')
    ),
    published_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_live_table_signals_owner
    ON live_table_signals(visit_id,sequence_id);
