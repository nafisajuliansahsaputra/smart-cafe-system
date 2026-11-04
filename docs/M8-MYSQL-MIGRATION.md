# M8 MySQL 8 migration (financially safe, staged)

Decision: migrate Smart Cafe System's backend database to **MySQL 8**
to broaden real full-stack/database skills demonstrated by NATSX.
This is a **fresh database port**, NOT a data copy from any existing
PostgreSQL/Neon instance. Do not delete Neon or use actual cafe records.
The portfolio must not describe MySQL as operational until proven.

## Source and status (2026-11-05)

- Existing **PostgreSQL 17/18** implementation remains the functional MVP,
  with 31 Flyway migrations and backend cash/payment/game flow tests.
- **MySQL SQL port drafted:** opt-in `db/mysql/V1-V31` (all 31 versions; **V1-V31 migrations and 7 core negative tests passed in earlier CI**, not application/production-certified)
  foundational tables, staff/guest revocable sessions, one-active-visit,
  immutable paid-only receipts, stock/cart/variant groups, join throttles,
  durable live outbox, serialized loan exclusivity, loss and condition
  history, fake-payment quarantine, cash shift custody, append-only audit,
  and menu image URLs. Every MySQL-specific trigger/constraint still needs
  exact MySQL CI and business E2E verification. MySQL expressions/trigger syntax are native MySQL 8.
- Added `mysql-connector-j`, `flyway-mysql` and MySQL 8.4 Testcontainers.
- `MySqlCoreParityIntegrationTest` creates a disposable MySQL instance,
  migrates it using `classpath:db/mysql`, and rejects duplicate active
  table visits, invalid FK/capacity, unpaid or mismatched receipts, and
  mutable issued receipt/order financial data. It also verifies the
  automatic item-stock trigger, case-insensitive unique item variants,
  and exclusive physical game loan assignments.
- **Runtime default remains PostgreSQL; MySQL backend is fail-closed before
  Flyway.** It must not be pointed at Aiven/other public MySQL yet.

## Invariants that cannot be weakened

1. Only one active visit per table, implemented with a nullable
   `active_table_key` generated column plus unique index, equivalent
   to PostgreSQL's partial unique index.
2. Receipts require an exact matching settled `payment_intents` amount
   and order ID; receipts cannot be updated or deleted.
3. Financial pricing fields on orders never change after submission.
4. A game copy cannot be assigned to two active loans concurrently.
5. At most one open cash drawer shift, all tenders attributed consistently,
   all shift, handoff and review audit records immutable.
6. Sandbox QRIS/VA is nonfinancial evidence; it cannot settle merchant
   payments, and cannot forge receipts.
7. All session tokens are hashed, join proof expiring, no static QR ordering.
8. No customer/real payment data in migration fixtures or test screenshots.

## Additional parity hardening on this branch

- PostgreSQL's default enum comparisons are case-sensitive. MySQL's
  common default text collation is often case-insensitive. Critical
  database states and financial methods now explicitly use
  `utf8mb4_bin` on the MySQL schema, including the core roles,
  table/order/kitchen statuses, receipt currency, game loans, digital
  payment attempts, sandbox event classifications and variance actions.
  The MySQL integration suite includes rejection tests for lowercase
  financial statuses and methods. Do not remove these constraints.
- V11's composite `outbox_events(aggregate_type,live_published_at,created_at,id)`
  index also serves V12, V14 and V17. Redundant duplicate copies of
  that index were removed to reduce free-tier storage overhead.
- `JdbcSqlDialect` handles a **narrow audited subset** of SQL dialect
  differences: idempotent staff-login throttle and cart insertions.
  The MySQL `ON DUPLICATE KEY UPDATE` form preserves the existing
  throttle counter and cart-to-visit binding and does not use unsafe
  `INSERT IGNORE`. Both cases have real MySQL integration tests.
- `JdbcUuid.read` supports native PostgreSQL UUID, MySQL CHAR(36)
  strings and validated 16-byte representations in service query
  mappings; MySQL UUID **parameter binding** and other SQL remain
  full-backend acceptance gates. Do not bypass the startup guard.
- **Verified CI evidence (not the latest branch head):** GitHub Actions
  [run 41902735530](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/41902735530)
  on commit `e8d80f7822c57f9c257b8d48208ae65b00dca639` completed
  successfully on 2026-11-05 WIB. Testcontainers MySQL 8.4 validated and
  **applied all 31 Flyway migrations** to a fresh disposable schema.
  `MySqlCoreParityIntegrationTest` ran **7 tests: 0 failures, 0 errors,
  0 skipped**. This supersedes the earlier V1-V14-only evidence, but does
  **not** prove an upgrade rehearsal, full backend business parity or
  the latest branch head. Later runs, including commit `54b8aa14`, were
  still queued when checked. This branch is not deployment-ready.

## Trigger privilege prerequisite for hosted MySQL

MySQL 8 with binary logging and `log_bin_trust_function_creators=0`
rejects creating triggers without administrative privileges (error 1419).
The disposable Testcontainers instance uses
`--log-bin-trust-function-creators=1` solely for CI. On a managed
provider such as Aiven Free, verify `SHOW VARIABLES LIKE
'log_bin_trust_function_creators';` and `SHOW GRANTS;` using a dedicated
empty test schema **before selecting the provider**. The host must permit
immutable-money and stock/audit triggers. Never replace the triggers
with unprotected API-only checks to bypass hosting limitations.

## Required before an actual MySQL deployment

- Run the **entire 31-version MySQL migration** against fresh and upgrade
  databases, then test concurrent unique constraints, trigger rejection,
  foreign keys, immutable audit evidence, stock/game loans and receipt
  settlement. The draft MySQL DDL uses generated nullable keys instead
  of PostgreSQL partial unique indexes. A green migration is necessary,
  but does not alone prove business parity.
- Replace PG-specific SQL in backend services, e.g. advisory transaction
  locks, `ON CONFLICT`, `::jsonb`, `jsonb_build_object`, data-returning
  casts, regex and epoch functions. Decide equivalently safe MySQL
  `GET_LOCK` lifecycle or explicit parent-row locks for concurrent cash
  and stock paths. Do NOT turn advisory locks into no-ops.
- Centralize JDBC UUID reading/writing: PostgreSQL `rs.getObject(...,
  UUID.class)` cannot be presumed to work with MySQL's CHAR(36).
- Run ALL existing Java, full guest-to-cash, KDS, waiter, reporting,
  game-loan, SSE and negative security E2E tests against **MySQL 8**.
- Test the final MySQL Docker backend on the chosen free host with
  outbound TLS, credential rotation, quotas and remote first-admin
  provisioning. Never switch hosted service with PG-only migrations.
- Run deliberate database migration/cutover **only with explicit owner
  approval** and a rollback and backup plan. For this project, since
  the hosted Neon review database is new and empty, prefer a new MySQL
  review database instead of migration of real data.

## Isolated local SQL verification

The MySQL schema port is deliberately opt-in and intended for
integration tests, **not** to enable the public application server.

```bash
cd backend
mvn -B -ntp -Dtest=MySqlCoreParityIntegrationTest test
```

Needs Docker Engine. The CI workflow
`.github/workflows/mysql8-parity.yml` runs exactly these tests on
GitHub Actions. The tests now target all 31 migration files and include
cash-shift and fake-payment negative cases. A MySQL database must NEVER use the PostgreSQL
`classpath:db/migration` scripts; it uses
`classpath:db/mysql`. Default application deployment continues
PostgreSQL until complete parity.

## Infrastructure status

- User's new Neon project/database remain intact, no sample app data
  imported, no further production/portfolio changes.
- Render Free returned HTTP 402, so it was NOT deployed; Aiven MySQL
  Free is only a proposed provider pending separate owner signup and
  verification that its plan does not require a card.
- No separate public Smart Cafe full-stack review URL exists yet.


## Remote free-host compatibility gate (added in M8 review)

Before choosing an Aiven MySQL Free review service, run the **read-only**
host check and then explicitly authorize its disposable trigger/CHECK probe:
[hosted MySQL preflight](M8-HOSTED-MYSQL-PREFLIGHT.md).
The script at `scripts/test-mysql-host-preflight.ps1` requires a separately
stored local MySQL client option file with hostname-verified TLS; no secrets
are committed. It creates and removes only a random `scs_probe_*` database
when `-RunWriteProbe` is explicitly specified.

Passing this check confirms the *remote host prerequisites only*. It is not
proof of MySQL application readiness, full V1-V31 Flyway migration, financial
transaction parity, deployment authorization or unrestricted free uptime.
Keep `MySqlPortReadinessGuard` in place until all those separate gates pass.
