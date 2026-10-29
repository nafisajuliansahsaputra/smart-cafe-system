-- Audit records are append-only and stay append-only.
-- Index latest-first browsing; search filters are parameterized server-side.
CREATE INDEX idx_game_copy_condition_events_recent
    ON physical_game_copy_condition_events(recorded_at DESC,id DESC);
CREATE INDEX idx_game_copy_condition_events_change_recent
    ON physical_game_copy_condition_events(after_condition,recorded_at DESC,id DESC);
