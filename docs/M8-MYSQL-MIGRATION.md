# M8 MySQL 8 migration (financially safe, staged)

Decision: migrate Smart Cafe System's backend database to **MySQL 8**
to broaden real full-stack/database skills demonstrated by NATSX.
This is a **fresh database port**, NOT a data copy from any existing
PostgreSQL/Neon instance. Do not delete Neon or use actual cafe records.
The portfolio must not describe MySQL as operational until proven.

## Source and status (2026-11-05)

- Existing **PostgreSQL 17/18** implementation remains the functional MVP,
  with 31 Flyway migrations and backend cash/payment/game flow tests.
- **MySQL stage 1 is implemented:** opt-in `db/mysql/V1-V5`
  foundational tables, staff/guest revocable sessions, table-visit unique
  active-visit constraint, immutable order financial snapshots, immutable
  paid-only receipts. MySQL expressions/trigger syntax are native MySQL 8.
- Added `mysql-connector-j`, `flyway-mysql` and MySQL 8.4 Testcontainers.
- `MySqlCoreParityIntegrationTest` creates a disposable MySQL instance,
  migrates it using `classpath:db/mysql`, and rejects duplicate active
  table visits, invalid FK/capacity, unpaid or mismatched receipts, and
  mutable issued receipt/order financial data.
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

## Required before an actual MySQL deployment

- Port MySQL equivalents of **V6-V31** in the exact order and preserve
  unique, foreign key, check, append-only and concurrency behavior.
  Several PG partial unique indexes need generated nullable columns.
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

The MySQL schema stage is deliberately opt-in and intended for
integration tests, **not** to enable the public application server.

```bash
cd backend
mvn -B -ntp -Dtest=MySqlCoreParityIntegrationTest test
```

Needs Docker Engine. The CI workflow
`.github/workflows/mysql8-parity.yml` runs exactly these tests on
GitHub Actions. A MySQL database must NEVER use the PostgreSQL
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
