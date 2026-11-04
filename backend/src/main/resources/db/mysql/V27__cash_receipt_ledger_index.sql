-- MySQL 8: bounded latest-first cash receipt ledger.
CREATE INDEX idx_receipts_issued_id_desc
  ON receipts(issued_at DESC,id DESC);
