-- MySQL 8: full, immutable inventory history searchable newest-first.
CREATE INDEX idx_game_copy_condition_events_recent
  ON physical_game_copy_condition_events(recorded_at DESC,id DESC);
CREATE INDEX idx_game_copy_condition_events_change_recent
  ON physical_game_copy_condition_events(after_condition,recorded_at DESC,id DESC);
