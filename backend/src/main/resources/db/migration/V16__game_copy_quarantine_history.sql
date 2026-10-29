-- Safe physical inventory quarantine: permanent, staff-attributed history.
-- Do not auto-charge a customer or infer damage liability from this state.
CREATE TABLE physical_game_copy_condition_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    copy_id uuid NOT NULL REFERENCES physical_game_copies(id),
    loan_id uuid REFERENCES physical_game_loans(id),
    actor_id uuid NOT NULL REFERENCES staff_users(id),
    before_condition varchar(20) NOT NULL
        CHECK (before_condition IN ('AVAILABLE','DAMAGED')),
    after_condition varchar(20) NOT NULL
        CHECK (after_condition IN ('AVAILABLE','DAMAGED')),
    reason varchar(300) NOT NULL CHECK (length(btrim(reason)) BETWEEN 5 AND 300),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT condition_event_transition CHECK (before_condition <> after_condition)
);
CREATE INDEX idx_game_copy_condition_events_copy
    ON physical_game_copy_condition_events(copy_id,recorded_at DESC);
