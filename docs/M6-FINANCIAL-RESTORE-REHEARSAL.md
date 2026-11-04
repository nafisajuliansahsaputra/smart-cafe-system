# M6 Financial-evidence PostgreSQL restore rehearsal

## Purpose

The current clean-start/restore acceptance already checks basic table counts,
schema/constraints, inventory, staff, roles, audit and Flyway metadata. Its
earlier fixture did **not** contain a fully settled cash/receipt/physical
custody and closed-drawer variance chain. Thus a restore might pass despite
losing or changing the financial evidence the cafe must be able to inspect.

This adds a **CI-only synthetic** money chain to the existing isolated
`pg_dump -Fc` -> separate-database `pg_restore` workflow. No production
data, merchant keys, real payments, hosted staging, deployment or migration
is touched.

## Business evidence included

`infra/fixtures/restore-financial-evidence.sql` seeds fixed,
synthetic UUIDs **after** `e2e-browser.sql` and the previous restore fixture,
and refuses a DB without the exact disposable `smart_cafe` name, known
synthetic manager/waiter/table identities and successful Flyway V30.

1. Synthetic waiter-owned visit, guest, served order and immutable line price
   snapshot: **Rp35,000**.
2. CASH intent marked paid **Rp35,000**; tender collected **Rp50,000**,
   change given **Rp15,000**.
3. Open drawer with float **Rp10,000** and tender assigned to that shift.
4. Waiter-to-cashier custody accepted explicitly by synthetic manager.
5. Issued immutable final receipt for **Rp35,000**, with synthetic JSON.
6. Closed shift with recorded cash **Rp35,000**, expected cash **Rp45,000**,
   physically counted **Rp44,000**, variance **-Rp1,000**.
7. Append-only manager `REQUEST_RECOUNT` note and attributed audit.

The existing restore script refuses to proceed if this entire positive
chain is missing or fails its money and custody joins. It still refuses
CI-less operation, a wrong source DB, or an already existing destination.

## Backup/restore verification

The smoke CI job creates a fresh V30 disposable PostgreSQL and fake staff,
seeds this chain and makes a PostgreSQL custom-format backup while the API
is running. It restores to a new separate database. **All public table row
counts and constraint/index fingerprints** must match.

Content checks use only non-printed MD5 fingerprints from normalized
`to_jsonb(row)::text` over all rows, sorted deterministically, from:

- the prior staff, roles, game copies, menu items, audits and Flyway rows;
- visits, guests, orders, order lines, payment intents, cash tenders;
- cash custody handoffs, drawer shifts, manager variance reviews;
- immutable receipts and physical game loans.

The source/restore digest streams must be identical. The script does not
log customer fields or financial snapshots and does not upload a backup
artifact. It rechecks the exact linked paid-money/custody/receipt/closed
drawer state inside the restored database, then tries illegal SQL updates
and deletes to ensure immutable receipt, shift, custody and variance
review triggers still reject mutation.

The destination database and temporary backup are destroyed by shell
cleanup; the CI Docker Compose job also removes all ephemeral volumes.

## Safety and limitations

The fixed IDs and monetary values are **not live business entries**;
never use this fixture or script against shared staging or production.
This is a **logical backup/restore** rehearsal, not point-in-time recovery,
streaming replication, disaster recovery, proof of encryption at rest,
RPO/RTO certification, live concurrent financial writes during dump,
historical payment-provider reconciliation or compliance sign-off.

Before any real pilot, independently provision an isolated staging DB,
secret vault, backups with lifecycle retention, restore credentials,
verification and alerting. Define RPO/RTO with an accountable owner,
and repeat backup/restore with authorized anonymized staging data.
