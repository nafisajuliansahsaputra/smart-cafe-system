-- Manager inventory condition filter and deterministic copy-code paging.
-- Search remains bounded (25 per page, page index <= 2000); no new PII.
CREATE INDEX idx_physical_game_copies_condition_copy_code
    ON physical_game_copies(condition,copy_code,id);
