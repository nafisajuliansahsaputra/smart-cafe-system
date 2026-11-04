-- Staff-reported, manager-reviewed missing physical games.
-- Reporting alone NEVER changes copy condition or loan ownership.
-- A confirmed loss is terminal only after explicit manager verification.
ALTER TABLE physical_game_loans
    DROP CONSTRAINT physical_game_loans_status_check,
    DROP CONSTRAINT loan_copy_requires_assignment,
    ADD COLUMN lost_at timestamptz,
    ADD COLUMN lost_confirmed_by uuid REFERENCES staff_users(id),
    ADD CONSTRAINT physical_game_loans_status_check
        CHECK (status IN ('REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING',
                         'RETURNED','CANCELLED','LOST')),
    ADD CONSTRAINT loan_copy_requires_assignment CHECK (
        (status IN ('REQUESTED','CANCELLED') AND copy_id IS NULL)
        OR (status IN ('ASSIGNED','IN_USE','RETURN_PENDING','RETURNED','LOST')
            AND copy_id IS NOT NULL)),
    ADD CONSTRAINT loan_loss_requires_manager CHECK (
        status <> 'LOST' OR (lost_at IS NOT NULL AND lost_confirmed_by IS NOT NULL));

CREATE TABLE physical_game_loss_incidents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id uuid NOT NULL REFERENCES physical_game_loans(id),
    reported_by uuid NOT NULL REFERENCES staff_users(id),
    reason varchar(300) NOT NULL CHECK (length(btrim(reason)) BETWEEN 5 AND 300),
    status varchar(24) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','DISMISSED','CONFIRMED_LOST')),
    reported_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by uuid REFERENCES staff_users(id),
    reviewed_at timestamptz,
    review_reason varchar(300),
    CONSTRAINT game_loss_review_complete CHECK (
        (status='OPEN' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_reason IS NULL)
        OR (status IN ('DISMISSED','CONFIRMED_LOST')
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND length(btrim(review_reason)) BETWEEN 5 AND 300))
);
CREATE UNIQUE INDEX uq_open_physical_game_loss
    ON physical_game_loss_incidents(loan_id) WHERE status='OPEN';
CREATE INDEX idx_physical_game_loss_review
    ON physical_game_loss_incidents(status,reported_at DESC,id DESC);

-- Use V17's single ordered game stream. Signals have no incident notes,
-- serials, guest identifiers, staff names or payment information.
ALTER TABLE live_game_signals DROP CONSTRAINT live_game_signals_event_type_check;
ALTER TABLE live_game_signals ADD CONSTRAINT live_game_signals_event_type_check CHECK (
    (loan_id IS NOT NULL AND event_type IN (
        'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED','GAME_LOAN_HANDED_OVER',
        'GAME_LOAN_RETURN_REQUESTED','GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED',
        'GAME_LOSS_REPORTED','GAME_LOSS_DISMISSED','GAME_LOSS_CONFIRMED'
    ))
    OR (game_id IS NOT NULL AND event_type='GAME_INVENTORY_CHANGED')
);
