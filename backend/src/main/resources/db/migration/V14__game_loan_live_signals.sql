-- Durable game-loan change markers. Never store a guest name, copy code,
-- table join proof, bearer token, or business payment/receipt data here.
CREATE INDEX idx_outbox_live_game_pending
    ON outbox_events(created_at,id)
    WHERE aggregate_type='GAME_LOAN' AND live_published_at IS NULL;

CREATE TABLE live_game_signals (
    sequence_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outbox_id uuid NOT NULL UNIQUE REFERENCES outbox_events(id),
    loan_id uuid NOT NULL REFERENCES physical_game_loans(id),
    event_type varchar(120) NOT NULL CHECK (event_type IN (
        'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED','GAME_LOAN_HANDED_OVER',
        'GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED'
    )),
    published_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_live_game_signals_loan ON live_game_signals(loan_id,sequence_id);
