-- MySQL 8: indexed manager inventory condition and bounded pagination.
CREATE INDEX idx_physical_game_copies_condition_copy_code
  ON physical_game_copies(`condition`,copy_code,id);
