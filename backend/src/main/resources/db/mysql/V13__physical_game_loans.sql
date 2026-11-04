-- MySQL 8: each game copy is a serialized physical asset.
-- Generated NULLABLE keys replace PostgreSQL partial UNIQUE indexes.
CREATE TABLE physical_games (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  name VARCHAR(120) NOT NULL UNIQUE,
  description VARCHAR(500),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE physical_game_copies (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  game_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  copy_code VARCHAR(80) NOT NULL UNIQUE,
  UNIQUE (id,game_id),
  `condition` VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
      CHECK (`condition` IN ('AVAILABLE','DAMAGED','LOST','RETIRED')),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (game_id) REFERENCES physical_games(id)
);
CREATE INDEX idx_physical_game_copies_available
  ON physical_game_copies(game_id,`condition`,copy_code);

CREATE TABLE physical_game_loans (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  visit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  game_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  copy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
    CONSTRAINT game_loan_status CHECK
      (status IN ('REQUESTED','ASSIGNED','IN_USE','RETURNED','CANCELLED')),
  requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  assigned_at DATETIME(6),
  handed_over_at DATETIME(6),
  returned_at DATETIME(6),
  assigned_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  handed_over_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  returned_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  active_copy_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
      CASE WHEN status IN ('ASSIGNED','IN_USE') THEN copy_id ELSE NULL END
    ) STORED,
  active_request_key CHAR(73) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
      CASE WHEN status IN ('REQUESTED','ASSIGNED','IN_USE')
        THEN CONCAT(visit_id,':',game_id) ELSE NULL END
    ) STORED,
  UNIQUE KEY uq_active_game_copy_loan (active_copy_key),
  UNIQUE KEY uq_active_game_request_visit (active_request_key),
  CONSTRAINT loan_copy_requires_assignment CHECK (
    (status IN ('REQUESTED','CANCELLED') AND copy_id IS NULL)
    OR (status IN ('ASSIGNED','IN_USE','RETURNED') AND copy_id IS NOT NULL)),
  CONSTRAINT loan_assignment_requires_time CHECK (
    status IN ('REQUESTED','CANCELLED') OR assigned_at IS NOT NULL),
  CONSTRAINT loan_handover_requires_time CHECK (
    status NOT IN ('IN_USE','RETURNED') OR handed_over_at IS NOT NULL),
  CONSTRAINT loan_return_requires_time CHECK (
    status <> 'RETURNED' OR returned_at IS NOT NULL),
  FOREIGN KEY (visit_id) REFERENCES table_visits(id),
  FOREIGN KEY (guest_id,visit_id) REFERENCES visit_guests(id,visit_id),
  FOREIGN KEY (game_id) REFERENCES physical_games(id),
  FOREIGN KEY (copy_id,game_id) REFERENCES physical_game_copies(id,game_id),
  FOREIGN KEY (assigned_by) REFERENCES staff_users(id),
  FOREIGN KEY (handed_over_by) REFERENCES staff_users(id),
  FOREIGN KEY (returned_by) REFERENCES staff_users(id)
);
CREATE INDEX idx_game_loans_visit_active
  ON physical_game_loans(visit_id,status);
CREATE INDEX idx_game_loans_guest
  ON physical_game_loans(guest_id,requested_at DESC);
