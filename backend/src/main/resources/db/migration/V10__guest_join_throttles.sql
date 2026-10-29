-- Atomic fixed-window public join throttle. Keys are HMACs, never raw IPs.
-- Shared PostgreSQL serializes counters safely across requests/instances.
CREATE TABLE guest_join_throttles (
    bucket_hash char(64) PRIMARY KEY,
    window_started_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_guest_join_throttle_cleanup ON guest_join_throttles(updated_at);
