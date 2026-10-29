-- Guest pickup requests must never release inventory until staff checks
-- the matching physical copy. Original V13 constraints remain immutable.
ALTER TABLE physical_game_loans
    DROP CONSTRAINT physical_game_loans_status_check,
    DROP CONSTRAINT loan_copy_requires_assignment,
    DROP CONSTRAINT loan_handover_requires_time,
    ADD COLUMN return_requested_at timestamptz,
    ADD CONSTRAINT physical_game_loans_status_check
        CHECK (status IN ('REQUESTED','ASSIGNED','IN_USE',
                         'RETURN_PENDING','RETURNED','CANCELLED')),
    ADD CONSTRAINT loan_copy_requires_assignment CHECK (
        (status IN ('REQUESTED','CANCELLED') AND copy_id IS NULL)
        OR (status IN ('ASSIGNED','IN_USE','RETURN_PENDING','RETURNED')
            AND copy_id IS NOT NULL)),
    ADD CONSTRAINT loan_handover_requires_time CHECK (
        status NOT IN ('IN_USE','RETURN_PENDING','RETURNED')
        OR handed_over_at IS NOT NULL),
    ADD CONSTRAINT loan_return_request_requires_time CHECK (
        status <> 'RETURN_PENDING' OR return_requested_at IS NOT NULL);

DROP INDEX uq_active_game_copy_loan;
CREATE UNIQUE INDEX uq_active_game_copy_loan ON physical_game_loans(copy_id)
    WHERE status IN ('ASSIGNED','IN_USE','RETURN_PENDING');
DROP INDEX uq_active_game_request_visit;
CREATE UNIQUE INDEX uq_active_game_request_visit ON physical_game_loans(visit_id,game_id)
    WHERE status IN ('REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING');
DROP INDEX idx_game_loans_visit_active;
CREATE INDEX idx_game_loans_visit_active ON physical_game_loans(visit_id,status)
    WHERE status IN ('REQUESTED','ASSIGNED','IN_USE','RETURN_PENDING');

ALTER TABLE live_game_signals
    DROP CONSTRAINT live_game_signals_event_type_check,
    ADD CONSTRAINT live_game_signals_event_type_check CHECK (event_type IN (
        'GAME_LOAN_REQUESTED','GAME_LOAN_ASSIGNED','GAME_LOAN_HANDED_OVER',
        'GAME_LOAN_RETURN_REQUESTED','GAME_LOAN_RETURNED','GAME_LOAN_CANCELLED'
    ));
