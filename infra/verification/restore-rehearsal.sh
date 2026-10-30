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
SELECT 'constraint|' || conrelid::regclass::text || '|' || conname ||
       '|' || pg_get_constraintdef(oid)
FROM pg_constraint
WHERE connamespace='public'::regnamespace
ORDER BY conrelid::regclass::text,conname;
SELECT 'index|' || tablename || '|' || indexname || '|' || indexdef
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
  echo "FAIL: staff, game, menu, audit or Flyway content differs." >&2; exit 1;
}
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
echo "PASS: table counts, schema constraints/indexes, six content signatures and audit links."
echo "LIMIT: synthetic CI only, not production backups, PITR or an RPO/RTO guarantee."
