-- MySQL 8: transactional live-publication bookkeeping is distinct from
-- business outbox processed_at, which must never be set by UI telemetry.
ALTER TABLE outbox_events ADD COLUMN live_published_at DATETIME(6);
CREATE INDEX idx_outbox_live_pending
  ON outbox_events(aggregate_type,live_published_at,created_at,id);

CREATE TABLE live_order_signals (
  sequence_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (outbox_id) REFERENCES outbox_events(id),
  FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE INDEX idx_live_order_signals_order ON live_order_signals(order_id,sequence_id);
