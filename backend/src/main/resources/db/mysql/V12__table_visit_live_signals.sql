-- MySQL 8: durable, scoped visit signals, no join-secret material.
CREATE INDEX idx_outbox_live_table_pending
  ON outbox_events(aggregate_type,live_published_at,created_at,id);
CREATE TABLE live_table_signals (
  sequence_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  visit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  table_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(120) NOT NULL
    CHECK (event_type IN (
      'TABLE_VISIT_OPENED','TABLE_JOIN_PROOF_ROTATED','TABLE_VISIT_CLOSED')),
  published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (outbox_id) REFERENCES outbox_events(id),
  FOREIGN KEY (visit_id) REFERENCES table_visits(id),
  FOREIGN KEY (table_id) REFERENCES cafe_tables(id)
);
CREATE INDEX idx_live_table_signals_owner ON live_table_signals(visit_id,sequence_id);
