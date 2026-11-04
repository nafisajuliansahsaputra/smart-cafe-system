-- Physical inventory is serialized by unit, not by game title or a UI count.
-- This migration has NO demo seeds, fees, deposits or real customer data.
CREATE TABLE physical_games (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(120) NOT NULL UNIQUE,
    description varchar(500),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE physical_game_copies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id uuid NOT NULL REFERENCES physical_games(id),
    copy_code varchar(80) NOT NULL UNIQUE,
    UNIQUE (id,game_id),
    condition varchar(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (condition IN ('AVAILABLE','DAMAGED','LOST','RETIRED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_physical_game_copies_available
    ON physical_game_copies(game_id,copy_code) WHERE condition='AVAILABLE';

CREATE TABLE physical_game_loans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id uuid NOT NULL REFERENCES table_visits(id),
    guest_id uuid NOT NULL,
    game_id uuid NOT NULL REFERENCES physical_games(id),
    copy_id uuid,
    FOREIGN KEY (copy_id,game_id) REFERENCES physical_game_copies(id,game_id),
    status varchar(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','ASSIGNED','IN_USE','RETURNED','CANCELLED')),
    requested_at timestamptz NOT NULL DEFAULT now(),
    assigned_at timestamptz,
    handed_over_at timestamptz,
    returned_at timestamptz,
    assigned_by uuid REFERENCES staff_users(id),
    handed_over_by uuid REFERENCES staff_users(id),
    returned_by uuid REFERENCES staff_users(id),
    CONSTRAINT loan_guest_in_same_visit FOREIGN KEY (guest_id,visit_id)
        REFERENCES visit_guests(id,visit_id),
    CONSTRAINT loan_copy_requires_assignment CHECK (
        (status IN ('REQUESTED','CANCELLED') AND copy_id IS NULL)
        OR (status IN ('ASSIGNED','IN_USE','RETURNED') AND copy_id IS NOT NULL)),
    CONSTRAINT loan_assignment_requires_time CHECK (
        status IN ('REQUESTED','CANCELLED') OR assigned_at IS NOT NULL),
    CONSTRAINT loan_handover_requires_time CHECK (
        status NOT IN ('IN_USE','RETURNED') OR handed_over_at IS NOT NULL),
    CONSTRAINT loan_return_requires_time CHECK (
        status <> 'RETURNED' OR returned_at IS NOT NULL)
);
-- A physical copy cannot be assigned to two visits, even on concurrent requests.
CREATE UNIQUE INDEX uq_active_game_copy_loan ON physical_game_loans(copy_id)
    WHERE status IN ('ASSIGNED','IN_USE');
-- At most one pending/active request for one game per table visit.
CREATE UNIQUE INDEX uq_active_game_request_visit ON physical_game_loans(visit_id,game_id)
    WHERE status IN ('REQUESTED','ASSIGNED','IN_USE');
CREATE INDEX idx_game_loans_visit_active ON physical_game_loans(visit_id,status)
    WHERE status IN ('REQUESTED','ASSIGNED','IN_USE');
CREATE INDEX idx_game_loans_guest ON physical_game_loans(guest_id,requested_at DESC);
