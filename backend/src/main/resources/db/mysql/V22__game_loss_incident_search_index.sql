-- MySQL 8: generated open priority replaces PostgreSQL boolean expression index.
ALTER TABLE physical_game_loss_incidents
  ADD COLUMN open_priority TINYINT
    GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN 1 ELSE 0 END) STORED;
CREATE INDEX idx_game_loss_incidents_open_first
  ON physical_game_loss_incidents(open_priority DESC,reported_at DESC,id DESC);
