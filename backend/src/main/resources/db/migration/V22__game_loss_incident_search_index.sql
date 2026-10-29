-- OPEN incidents are manager priorities; use the exact queue ordering
-- for bounded paging without scanning all historical resolved reports.
-- Does not modify or remove append-only incident/audit evidence.
CREATE INDEX idx_game_loss_incidents_open_first
    ON physical_game_loss_incidents ((status='OPEN') DESC,reported_at DESC,id DESC);
