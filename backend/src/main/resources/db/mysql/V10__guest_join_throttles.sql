-- MySQL 8: guest join rate limits store HMACs, never raw IP addresses.
CREATE TABLE guest_join_throttles (
  bucket_hash CHAR(64) PRIMARY KEY,
  window_started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_guest_join_throttle_cleanup
  ON guest_join_throttles(updated_at);
