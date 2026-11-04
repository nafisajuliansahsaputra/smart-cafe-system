-- MySQL 8: durable, scoped visit signals, no join-secret material.
-- V11's idx_outbox_live_pending already covers all aggregate types here.
-- Do not duplicate the same four-column outbox index on free-tier storage.
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
