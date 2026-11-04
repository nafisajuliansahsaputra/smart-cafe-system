-- MySQL 8: found LOST physical inventory is quarantined as DAMAGED.
ALTER TABLE physical_game_copy_condition_events DROP CHECK game_condition_before;
ALTER TABLE physical_game_copy_condition_events
  ADD CONSTRAINT game_condition_before CHECK (
    before_condition IN ('AVAILABLE','DAMAGED','LOST')),
  ADD CONSTRAINT game_found_copy_must_be_quarantined CHECK (
    before_condition <> 'LOST' OR
    (after_condition='DAMAGED' AND loan_id IS NOT NULL));
