-- Found LOST physical units are never immediately available. A verified
-- manager recovery moves LOST -> DAMAGED quarantine and writes the same
-- append-only attributed condition history used for inspected restorations.
-- Loan LOST and confirmed loss incident remain immutable historical facts.
ALTER TABLE physical_game_copy_condition_events
    DROP CONSTRAINT physical_game_copy_condition_events_before_condition_check,
    ADD CONSTRAINT physical_game_copy_condition_events_before_condition_check
        CHECK (before_condition IN ('AVAILABLE','DAMAGED','LOST')),
    ADD CONSTRAINT game_found_copy_must_be_quarantined
        CHECK (before_condition <> 'LOST' OR
            (after_condition='DAMAGED' AND loan_id IS NOT NULL));
