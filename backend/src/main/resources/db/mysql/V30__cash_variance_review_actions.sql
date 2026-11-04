-- MySQL 8: immutable, manager-attributed cash shortage/excess review.
CREATE TABLE cash_variance_review_actions (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  shift_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action_code VARCHAR(40) COLLATE utf8mb4_bin NOT NULL
    CHECK (action_code IN ('REQUEST_RECOUNT','DOCUMENT_EVIDENCE','ESCALATE')),
  note VARCHAR(300) NOT NULL CHECK (CHAR_LENGTH(TRIM(note)) BETWEEN 10 AND 300),
  idempotency_key VARCHAR(128) NOT NULL
    CHECK (REGEXP_LIKE(idempotency_key,'^[A-Za-z0-9_-]{8,128}$')),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_cash_variance_shift_key UNIQUE (shift_id,idempotency_key),
  FOREIGN KEY (shift_id) REFERENCES cash_drawer_shifts(id),
  FOREIGN KEY (actor_staff_id) REFERENCES staff_users(id)
);
CREATE INDEX idx_cash_variance_history
  ON cash_variance_review_actions(shift_id,created_at DESC,id DESC);

DELIMITER //
CREATE TRIGGER cash_variance_review_no_update
  BEFORE UPDATE ON cash_variance_review_actions FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='Cash variance investigation history is append-only';
END//
CREATE TRIGGER cash_variance_review_no_delete
  BEFORE DELETE ON cash_variance_review_actions FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT='Cash variance investigation history is append-only';
END//
DELIMITER ;
