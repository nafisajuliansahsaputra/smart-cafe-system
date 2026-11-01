#!/usr/bin/env bash
# Isolated CI-only PostgreSQL restore rehearsal. Never use real merchant data.
set -euo pipefail

if [[ "$(printenv CI || true)" != "true" ||
      "$(printenv SCS_RESTORE_TEST_ISOLATED || true)" != "yes" ]]; then
  echo "REFUSED: requires CI=true and explicit isolated restore opt-in." >&2
  exit 2
fi

source_db="$(docker compose exec -T db sh -c 'printf "%s" "$POSTGRES_DB"')"
if [[ "$source_db" != "smart_cafe" ]]; then
  echo "REFUSED: only disposable smart_cafe CI database allowed." >&2
  exit 2
fi
restore_db="smart_cafe_ci_restore_verify"
backup="/tmp/scs-restore-rehearsal-$$.dump"
created=0

psql_db() {
  local database="$1" sql="$2"
  docker compose exec -T db sh -c '
    exec psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -c "$2"
  ' sh "$database" "$sql"
}
cleanup() {
  if [[ "$created" == "1" ]]; then
    docker compose exec -T db sh -c '
      dropdb -U "$POSTGRES_USER" --if-exists "$1"
    ' sh "$restore_db" >/dev/null || echo "WARNING: restore DB cleanup failed" >&2
  fi
  docker compose exec -T db sh -c 'rm -f "$1"' sh "$backup" >/dev/null ||
    echo "WARNING: temporary archive cleanup failed" >&2
}
trap cleanup EXIT

# Positive proof that only CI fixture data is present; no automatic seeding.
fixture_count="$(psql_db "$source_db" "
  SELECT count(*) FROM staff_users WHERE
  login IN ('e2e-waiter','e2e-cashier','e2e-kitchen','e2e-manager')
")"
audit_count="$(psql_db "$source_db" "
  SELECT count(*) FROM audit_logs WHERE correlation_id='ci-restore-synthetic-only'
")"
copy_count="$(psql_db "$source_db" "
  SELECT count(*) FROM physical_game_copies WHERE copy_code='UNO-E2E-001'
")"
financial_count="$(psql_db "$source_db" "
  SELECT count(*) FROM cash_drawer_shifts s
  JOIN cash_tenders t ON t.cash_shift_id=s.id
  JOIN cash_tender_handoffs h ON h.tender_id=t.id
  JOIN payment_intents p ON p.id=t.payment_intent_id
  JOIN receipts r ON r.payment_intent_id=p.id
  JOIN cash_variance_review_actions v ON v.shift_id=s.id
  WHERE s.id='88888888-8888-4888-8888-888888888806'
    AND s.closed_at IS NOT NULL AND s.difference_idr=-1000
    AND s.recorded_cash_idr=35000 AND s.expected_drawer_idr=45000
    AND s.counted_cash_idr=44000 AND t.amount_received_idr=50000
    AND t.change_given_idr=15000 AND h.received_at IS NOT NULL
    AND p.aggregate_status='PAID' AND p.amount_paid_idr=35000
    AND r.id='88888888-8888-4888-8888-888888888808'
    AND r.total_idr=35000 AND v.action_code='REQUEST_RECOUNT'
")"
if [[ "$financial_count" != "1" ]]; then
  echo "REFUSED: synthetic paid cash, receipt, custody and variance fixture absent." >&2
  exit 2
fi

if [[ "$fixture_count" != "4" || "$audit_count" != "1" || "$copy_count" != "1" ]]; then
  echo "REFUSED: expected synthetic staff, game and audit fixture absent." >&2
  exit 2
fi
existing="$(psql_db "$source_db" "
  SELECT count(*) FROM pg_database WHERE datname='smart_cafe_ci_restore_verify'
")"
if [[ "$existing" != "0" ]]; then
  echo "REFUSED: restore destination already exists; cannot overwrite." >&2
  exit 2
fi

# Snapshot while source API is running; no source DB drop or overwrite.
docker compose exec -T db sh -c '
  test ! -e "$1" &&
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-acl -f "$1"
' sh "$backup"
docker compose exec -T db sh -c '
  pg_restore -l "$1" | grep -q "TABLE DATA public physical_game_copies"
' sh "$backup" || {
  echo "FAIL: physical game inventory omitted from archive." >&2
  exit 1
}

docker compose exec -T db sh -c 'createdb -U "$POSTGRES_USER" "$1"' sh "$restore_db"
created=1
docker compose exec -T db sh -c '
  pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$1" "$2"
' sh "$restore_db" "$backup"

table_counts() {
  local database="$1"
  docker compose exec -T db sh -c '
    exec psql -X -q -t -A -F "|" -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"
  ' sh "$database" <<'SQL'
SELECT format('SELECT %L, count(*) FROM %I.%I;',
              tablename,schemaname,tablename)
FROM pg_tables WHERE schemaname='public' ORDER BY tablename
\gexec
SQL
}
schema_fingerprint() {
  local database="$1"
  docker compose exec -T db sh -c '
    exec psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"
  ' sh "$database" <<'SQL'
-- pg_restore parses some varchar[] CHECK expressions into equivalent
-- text[] AST forms. Compare constraint identity/type/validation/keys; prove
-- two critical CHECK/FK behaviors with actual rejected writes below.
SELECT 'constraint|' || conrelid::regclass::text || '|' || conname ||
       '|' || contype::text || '|' || convalidated::text || '|' ||
       coalesce(conkey::text,'') || '|' ||
       CASE WHEN contype='c' THEN 'CHECK_VALIDATED'
            ELSE pg_get_constraintdef(oid) END
FROM pg_constraint
WHERE connamespace='public'::regnamespace
ORDER BY conrelid::regclass::text,conname;
-- PostgreSQL also canonicalizes partial-index varchar[] predicates.
-- Keep full index structure, partial-index presence and ordered literal
-- status values; compare byte-for-byte definitions for non-partial indexes.
SELECT 'index|' || tablename || '|' || indexname || '|' ||
  CASE WHEN position(' WHERE ' IN indexdef)>0 THEN
    split_part(indexdef,' WHERE ',1) || ' WHERE [PARTIAL:' ||
    coalesce((SELECT string_agg(labels[1],',' ORDER BY ordinal)
      FROM regexp_matches(indexdef,'''([A-Z_]+)''','g')
        WITH ORDINALITY AS m(labels,ordinal)),'') || ']'
  ELSE indexdef END
FROM pg_indexes WHERE schemaname='public'
ORDER BY tablename,indexname;
SQL
}
data_fingerprint() {
  local database="$1"
  docker compose exec -T db sh -c '
    exec psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"
  ' sh "$database" <<'SQL'
SELECT 'staff|' || md5(coalesce(string_agg(
  id::text || '/' || login || '/' || enabled::text, '|'
  ORDER BY id),'')) FROM staff_users;
SELECT 'roles|' || md5(coalesce(string_agg(
  staff_user_id::text || '/' || role_key, '|'
  ORDER BY staff_user_id,role_key),'')) FROM staff_role_assignments;
SELECT 'games|' || md5(coalesce(string_agg(
  id::text || '/' || game_id::text || '/' || copy_code || '/' || condition, '|'
  ORDER BY id),'')) FROM physical_game_copies;
SELECT 'menu|' || md5(coalesce(string_agg(
  id::text || '/' || name || '/' || base_price_idr::text, '|'
  ORDER BY id),'')) FROM menu_items;
SELECT 'audit|' || md5(coalesce(string_agg(
  id::text || '/' || operation || '/' || resource_id::text || '/' ||
  correlation_id || '/' || occurred_at::text, '|' ORDER BY id),'')) FROM audit_logs;
SELECT 'flyway|' || md5(coalesce(string_agg(
  installed_rank::text || '/' || version || '/' || checksum::text || '/' || success::text,
  '|' ORDER BY installed_rank),'')) FROM flyway_schema_history;
SELECT 'visits|' || md5(coalesce(string_agg(to_jsonb(v)::text,'|' ORDER BY v.id),''))
  FROM table_visits v;
SELECT 'guests|' || md5(coalesce(string_agg(to_jsonb(g)::text,'|' ORDER BY g.id),''))
  FROM visit_guests g;
SELECT 'orders|' || md5(coalesce(string_agg(to_jsonb(o)::text,'|' ORDER BY o.id),''))
  FROM orders o;
SELECT 'order_lines|' || md5(coalesce(string_agg(to_jsonb(l)::text,'|' ORDER BY l.id),''))
  FROM order_lines l;
SELECT 'payments|' || md5(coalesce(string_agg(to_jsonb(p)::text,'|' ORDER BY p.id),''))
  FROM payment_intents p;
SELECT 'cash_tenders|' || md5(coalesce(string_agg(to_jsonb(t)::text,'|' ORDER BY t.id),''))
  FROM cash_tenders t;
SELECT 'handoffs|' || md5(coalesce(string_agg(to_jsonb(h)::text,'|' ORDER BY h.tender_id),''))
  FROM cash_tender_handoffs h;
SELECT 'drawer_shifts|' || md5(coalesce(string_agg(to_jsonb(s)::text,'|' ORDER BY s.id),''))
  FROM cash_drawer_shifts s;
SELECT 'variance_reviews|' || md5(coalesce(string_agg(to_jsonb(v)::text,'|' ORDER BY v.id),''))
  FROM cash_variance_review_actions v;
SELECT 'receipts|' || md5(coalesce(string_agg(to_jsonb(r)::text,'|' ORDER BY r.id),''))
  FROM receipts r;
SELECT 'game_loans|' || md5(coalesce(string_agg(to_jsonb(l)::text,'|' ORDER BY l.id),''))
  FROM physical_game_loans l;
SQL
}
source_counts="$(table_counts "$source_db")"
restore_counts="$(table_counts "$restore_db")"
[[ "$source_counts" == "$restore_counts" ]] || {
  echo "FAIL: public table row counts differ after restore." >&2; exit 1;
}
source_schema="$(schema_fingerprint "$source_db")"
restored_schema="$(schema_fingerprint "$restore_db")"
if [[ "$source_schema" != "$restored_schema" ]]; then
  echo "FAIL: constraint/index metadata differs after restore. Synthetic CI diff:" >&2
  diff -u <(printf '%s\n' "$source_schema") \
          <(printf '%s\n' "$restored_schema") | head -100 >&2 || true
  exit 1
fi
[[ "$(data_fingerprint "$source_db")" == "$(data_fingerprint "$restore_db")" ]] || {
  echo "FAIL: restored staff, game, menu, audit, Flyway or financial evidence differs." >&2; exit 1;
}
# Demonstrate that restored constraints still enforce real invalid states,
# instead of relying solely on textual schema-object metadata.
docker compose exec -T db sh -c '
  exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"
' sh "$restore_db" <<'SQL'
DO $verify$
BEGIN
  BEGIN
    UPDATE physical_game_copies SET condition='INVALID'
    WHERE copy_code='UNO-E2E-001';
    RAISE EXCEPTION 'invalid game-copy condition accepted after restore';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE physical_game_copies SET game_id=gen_random_uuid()
    WHERE copy_code='UNO-E2E-001';
    RAISE EXCEPTION 'broken game-copy foreign key accepted after restore';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
END $verify$;
SQL

# Prove restored financial chains and immutable triggers with rejected writes.
# DO exception blocks roll back each attempted mutation individually.
docker compose exec -T db sh -c '
  exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"
' sh "$restore_db" <<'SQL'
DO $verify_cash$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM cash_drawer_shifts s
    JOIN cash_tenders t ON t.cash_shift_id=s.id
    JOIN cash_tender_handoffs h ON h.tender_id=t.id
    JOIN payment_intents p ON p.id=t.payment_intent_id
    JOIN receipts r ON r.payment_intent_id=p.id
    JOIN cash_variance_review_actions v ON v.shift_id=s.id
    WHERE s.id='88888888-8888-4888-8888-888888888806'
      AND s.closed_at IS NOT NULL AND s.difference_idr=-1000
      AND s.expected_drawer_idr=45000 AND s.recorded_cash_idr=35000
      AND s.counted_cash_idr=44000 AND p.aggregate_status='PAID'
      AND p.amount_paid_idr=35000 AND r.total_idr=35000
      AND h.received_by='44444444-4444-4444-8444-444444444444'
      AND h.received_at IS NOT NULL AND v.action_code='REQUEST_RECOUNT'
  ) THEN
    RAISE EXCEPTION 'restored cash evidence, receipt or custody linkage invalid';
  END IF;
  BEGIN
    UPDATE receipts SET total_idr=1
    WHERE id='88888888-8888-4888-8888-888888888808';
    RAISE EXCEPTION 'restored immutable receipt can be changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM = 'restored immutable receipt can be changed' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE cash_drawer_shifts SET counted_cash_idr=43000, difference_idr=-2000
    WHERE id='88888888-8888-4888-8888-888888888806';
    RAISE EXCEPTION 'restored closed shift can be changed';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE cash_tender_handoffs SET received_by='11111111-1111-4111-8111-111111111111'
    WHERE tender_id='88888888-8888-4888-8888-888888888807';
    RAISE EXCEPTION 'restored confirmed custody can be changed';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    DELETE FROM cash_variance_review_actions
    WHERE shift_id='88888888-8888-4888-8888-888888888806';
    RAISE EXCEPTION 'restored variance review can be deleted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $verify_cash$;
SQL

audit_link="$(psql_db "$restore_db" "
  SELECT count(*) FROM audit_logs a
  JOIN staff_users u ON u.id=a.actor_id
  JOIN physical_game_copies c ON c.id=a.resource_id
  WHERE a.correlation_id='ci-restore-synthetic-only'
    AND u.login='e2e-manager' AND c.copy_code='UNO-E2E-001'
")"
[[ "$audit_link" == "1" ]] || {
  echo "FAIL: restored audit actor/inventory reference inconsistent." >&2; exit 1;
}
echo "PASS: PostgreSQL custom archive restored into a separate, newly created database."
echo "PASS: all table counts, constraints/indexes, 17 content signatures including paid money and receipts, immutable triggers and audit links."
echo "LIMIT: synthetic CI only, not production backups, PITR or an RPO/RTO guarantee."
