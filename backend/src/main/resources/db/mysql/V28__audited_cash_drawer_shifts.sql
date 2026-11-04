-- MySQL 8: exactly one live physical till shift at a time.
CREATE TABLE cash_drawer_shifts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  opened_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  opening_float_idr BIGINT NOT NULL
    CHECK (opening_float_idr BETWEEN 0 AND 1000000000000),
  open_key VARCHAR(128) NOT NULL UNIQUE
    CHECK (REGEXP_LIKE(open_key,'^[A-Za-z0-9_-]{8,128}$')),
  opened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  closed_at DATETIME(6),
  closed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  close_key VARCHAR(128)
    CHECK (close_key IS NULL OR REGEXP_LIKE(close_key,'^[A-Za-z0-9_-]{8,128}$')),
  counted_cash_idr BIGINT CHECK (counted_cash_idr BETWEEN 0 AND 1000000000000),
  recorded_cash_idr BIGINT CHECK (recorded_cash_idr BETWEEN 0 AND 1000000000000),
  expected_drawer_idr BIGINT CHECK (expected_drawer_idr BETWEEN 0 AND 1000000000000),
  difference_idr BIGINT,
  closing_reason VARCHAR(300),
  active_shift_slot TINYINT
    GENERATED ALWAYS AS (CASE WHEN closed_at IS NULL THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uq_one_active_cash_drawer_shift (active_shift_slot),
  CONSTRAINT shift_close_all_or_none CHECK (
    (closed_at IS NULL AND closed_by IS NULL AND close_key IS NULL
     AND counted_cash_idr IS NULL AND recorded_cash_idr IS NULL
     AND expected_drawer_idr IS NULL AND difference_idr IS NULL
     AND closing_reason IS NULL)
    OR (closed_at IS NOT NULL AND closed_at >= opened_at AND closed_by IS NOT NULL
     AND close_key IS NOT NULL AND counted_cash_idr IS NOT NULL
     AND recorded_cash_idr IS NOT NULL AND expected_drawer_idr IS NOT NULL
     AND difference_idr IS NOT NULL AND closing_reason IS NOT NULL)
  ),
  CONSTRAINT shift_expected_matches_cash CHECK (
    expected_drawer_idr IS NULL
    OR expected_drawer_idr = opening_float_idr + recorded_cash_idr),
  CONSTRAINT shift_difference_matches_count CHECK (
    difference_idr IS NULL OR difference_idr = counted_cash_idr - expected_drawer_idr),
  FOREIGN KEY (opened_by) REFERENCES staff_users(id),
  FOREIGN KEY (closed_by) REFERENCES staff_users(id)
);
CREATE INDEX idx_cash_drawer_shifts_recent
  ON cash_drawer_shifts(opened_at DESC,id DESC);
CREATE INDEX idx_cash_tenders_recorded_at
  ON cash_tenders(recorded_at);

DELIMITER //
CREATE TRIGGER cash_drawer_shift_protection_update
  BEFORE UPDATE ON cash_drawer_shifts FOR EACH ROW
BEGIN
  IF OLD.closed_at IS NOT NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='Completed cash drawer shift evidence is immutable';
  END IF;
END//
CREATE TRIGGER cash_drawer_shift_protection_delete
  BEFORE DELETE ON cash_drawer_shifts FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='Completed cash drawer shift evidence is immutable';
END//
DELIMITER ;
