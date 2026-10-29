-- Synthetic, disposable CI-only accounts for long-poll load testing.
-- NEVER use on an existing cafe/staging/production database.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

WITH one_hash AS (
  SELECT crypt('perf-live-ci-only-2026-secret', gen_salt('bf', 8)) AS hash
), seeded AS (
  INSERT INTO staff_users (id,login,password_hash)
  SELECT gen_random_uuid(),
    'perf-live-cashier-' || lpad(n::text, 2, '0'),
    one_hash.hash
  FROM generate_series(1,16) AS n CROSS JOIN one_hash
  ON CONFLICT (login)
  DO UPDATE SET password_hash=EXCLUDED.password_hash
  RETURNING id
)
INSERT INTO staff_role_assignments (staff_user_id,role_key)
SELECT id,'CASHIER' FROM seeded
ON CONFLICT DO NOTHING;
