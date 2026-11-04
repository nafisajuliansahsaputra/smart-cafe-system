-- Bounded cashier receipt ledger: range filter and stable newest-first page scan.
-- No changes to money, reconciliation, or existing immutable receipt triggers.
CREATE INDEX idx_receipts_issued_id_desc ON receipts(issued_at DESC, id DESC);
