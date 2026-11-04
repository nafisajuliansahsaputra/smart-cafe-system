-- MySQL 8: live game inventory signals with exactly one loan/game owner.
ALTER TABLE live_game_signals
  ADD COLUMN game_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  MODIFY COLUMN loan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD CONSTRAINT fk_live_game_signal_game FOREIGN KEY (game_id) REFERENCES physical_games(id);
ALTER TABLE live_game_signals
  ADD CONSTRAINT live_game_signal_exactly_one CHECK (
    (loan_id IS NOT NULL) <> (game_id IS NOT NULL));
ALTER TABLE live_game_signals DROP CHECK live_game_signal_type;
ALTER TABLE live_game_signals
  ADD CONSTRAINT live_game_signal_type CHECK (
    (loan_id IS NOT NULL AND event_type IN (
      'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED',
      'GAME_LOAN_HANDED_OVER','GAME_LOAN_RETURN_REQUESTED',
      'GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED'))
    OR (game_id IS NOT NULL AND event_type='GAME_INVENTORY_CHANGED'));
CREATE INDEX idx_outbox_live_inventory_pending
  ON outbox_events(aggregate_type,live_published_at,created_at,id);
CREATE INDEX idx_live_game_inventory_signals_game
  ON live_game_signals(game_id,sequence_id);
