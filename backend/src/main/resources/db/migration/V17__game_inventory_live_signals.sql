-- An authenticated catalogue watcher must see copy additions, stock
-- allocations, returns and manager condition changes without borrowing.
-- Reuse the existing game live sequence and one SSE subscription per page.
-- Loan signals are private; inventory signals carry only a game ID.
ALTER TABLE live_game_signals
    ADD COLUMN game_id uuid REFERENCES physical_games(id),
    ALTER COLUMN loan_id DROP NOT NULL;

ALTER TABLE live_game_signals ADD CONSTRAINT live_game_signal_exactly_one
    CHECK ((loan_id IS NOT NULL) <> (game_id IS NOT NULL));

ALTER TABLE live_game_signals DROP CONSTRAINT live_game_signals_event_type_check;
ALTER TABLE live_game_signals ADD CONSTRAINT live_game_signals_event_type_check
    CHECK (
        (loan_id IS NOT NULL AND event_type IN (
            'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED',
            'GAME_LOAN_HANDED_OVER','GAME_LOAN_RETURN_REQUESTED',
            'GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED'
        ))
        OR (game_id IS NOT NULL AND event_type='GAME_INVENTORY_CHANGED')
    );

CREATE INDEX idx_outbox_live_inventory_pending
    ON outbox_events(created_at,id)
    WHERE aggregate_type='GAME_INVENTORY' AND live_published_at IS NULL;
CREATE INDEX idx_live_game_inventory_signals_game
    ON live_game_signals(game_id,sequence_id) WHERE game_id IS NOT NULL;
