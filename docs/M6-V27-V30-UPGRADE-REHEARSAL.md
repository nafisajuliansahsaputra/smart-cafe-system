# M6 V27 → V30 in-place PostgreSQL migration rehearsal

## Scope

A dedicated Java 21/JUnit/PostgreSQL 17 Testcontainers test,
`LegacyUpgradeToV30IntegrationTest`, exercises **a real in-place Flyway
migration of a database already containing historical money and audit records**.
This is distinct from existing clean-start V30 CI and restore-rehearsal jobs.

No remote database, permanent Docker volume, live customer, real payment
provider or production credential is used. The PostgreSQL database is created
from scratch inside the isolated Testcontainer and destroyed after the test.

## Exact test sequence

1. Start a brand-new PostgreSQL 17 test container, with an explicitly synthetic
   database name and test-only credentials.
2. Run the same bundled Flyway migrations that the application uses, but set
   the Flyway target to **V27**, ensuring V28/V29/V30 do not yet exist.
3. Insert realistic **synthetic** legacy business evidence:
   - manager and waiter roles
   - table visit, guest and menu item
   - served order and immutable order-line financial snapshots
   - paid cash payment intent and existing net cash tender
   - issued immutable PAID-only receipt
   - physical game, copy and returned game loan
   - stable staff-attributed audit entry.
4. Read fixed-column snapshots for each legacy row before migration.
   The cash tender is old and **has no cash_shift_id** at V27.
5. Run Flyway V28→V30 in the **same** database, with no clear, clean,
   drop, recreate or fixture replacement.
6. Assert every pre-existing row/amount/receipt number/audit/game-loan
   and snapshot column is identical to the pre-upgrade value.
7. Assert exactly three new, successful Flyway version records (28/29/30),
   that new tables/columns exist, and historical cash has null shift ID
   instead of pretending it was in a newly created cash drawer.
8. Insert new synthetic after-upgrade data: an active cash shift, another
   PAID cash tender assigned to that shift, waiter handoff, confirmation,
   immutable closed drawer snapshot with a deliberate negative discrepancy,
   and a manager-only append-only investigation record.
9. Verify the database rejects overlapping open shifts, forged handoffs on
   legacy tenders, mutations of issued receipts, handoff evidence, closed
   shift amounts and variance-review records.
10. Check old rows a **second time** after these new operations and confirm
    a repeated Flyway migration executes zero further migrations.

The test is automatically included in the existing Spring Boot Maven test
suite and also runs independently in a new `upgrade-rehearsal` job of
`.github/workflows/ci.yml`. Both test the same candidate SHA.

## Why this matters

A clean database migration proves the SQL installs successfully on an empty
database, **not** that historic money and audit evidence survive upgrades.
The new test directly covers the V27→V28→V29→V30 transition.

## Limitations / gates

This is an *in-place, non-production* rehearsal of one known schema transition,
not proof that every historical deployed schema can upgrade. It does not test
arbitrary manual schema drift, concurrent user traffic during migrations,
PostgreSQL rolling restarts, external provider side effects, encrypted
historical customer PII, large data sets, locks/timeouts, WAL/PITR, or disaster
recovery. Production still requires tested backup/restore, approved migration
windows, staging-specific rehearsal against authorized schema snapshots and
independent operations/security sign-off.

Never run `infra/fixtures/e2e-browser.sql` or this rehearsal's fixture SQL
against any shared, real-cafe, or production database.
