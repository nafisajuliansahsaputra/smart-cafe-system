# M8 MySQL real-environment acceptance and remaining release gates

**Recorded:** 5 November 2026 WIB. This is an engineering evidence ledger, not a public launch approval.

## Verified without real customers

- The owner verified Aiven Free MySQL 8.4.11 with certificate-verified TLS,
  supported trigger/strict CHECK semantics, and automatic scratch database
  cleanup. Credentials stay local and must never enter GitHub.
- The owner ran hosted Flyway V1-V14 then V15-V31, validated all **31**
  migrations, proved a no-op repeated migration, and successfully deleted
  the temporary schema. **No persistent Aiven application database was
  migrated or provisioned by this test.**
- MySQL 8.4 Testcontainers integration suites on the stacked draft branches
  have verified guest/table UUID binding, staff login and throttle counters,
  cart, modifier pricing, cash checkout, idempotency, stock, OCC order state,
  KDS, waiter delivery, paid-only immutable receipts, cash collection,
  waiter-to-cashier handoff, till open/close and physical-game copy exclusion.
- [PR #101 MySQL Actions](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/41931482512)
  passed **24/24**, and all seven latest PR #101 Actions workflows finished
  successfully including full legacy CI.
- [PR #102 MySQL Actions](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/41932712088)
  passed **25/25, zero errors, BUILD SUCCESS** after fixing reserved-identifier
  MySQL differences. This verifies the **synthetic physical loan lifecycle**,
  not the loss-review and damaged-item incident service.

## Current code location

These changes are intentionally staged as **draft, stacked PRs** and have
**NOT been merged** into main: #89 MySQL 8 schema, #90 Aiven host checks,
#91 UUID, #92 join throttle, #93 staff auth, #94 cart, #95 transaction
checkout fence, #96 outbox JSON, #97 cash checkout, #98 OCC/KDS,
#99 receipts, #100 cash tender, #101 shift/custody, #102 physical games.

The MySQL runtime guard stays **enabled**. Do not remove it to obtain an
appealing but incorrect public demo.

## Still OPEN before a public review

1. Port the actual **game-loss incident** review and recovery workflow,
   manager authorization, immutability and concurrency to MySQL.
2. Audit remaining catalog, table-lifecycle, waiter/admin, staff management,
   reports, stock/finance, digital fake sandbox, SSE/outbox and audit SQL for
   PostgreSQL-only operators and UUID/time conversions. A green core MySQL
   test does not cover every endpoint.
3. Run the full Spring Boot application, authenticated REST/browser E2E,
   guest QR/NFC through order, cash settlement, kitchen, waiter, receipts,
   game loan, denial and recovery on **MySQL**; confirm origin/TLS/CSRF,
   role restrictions, concurrency and idempotency.
4. Rehearse backup/restore and migration rollback on a **persistent isolated
   Aiven review database** without production or customer data, and record
   Aiven Free quotas, connection SSL configuration and availability.
5. Select a **genuinely no-card, ongoing** remote backend hosting option
   with enough JVM memory and Aiven egress, verify the provider's actual
   signup rules and uptime behavior, then deploy backend and frontend
   behind HTTPS with validated read/write health and monitoring.
6. The owner must review the public website before any portfolio change.
   Real merchant credentials, live QRIS/VA and real customer phone delivery
   are separate permission gates, never part of the synthetic demo.

## Deployment truth

**No full-stack public Smart Cafe URL has been deployed.** No changes were
made to the existing PostgreSQL/Neon apps, Aiven customer database, real
merchant settings or the NATSX portfolio. Never describe these draft PRs
as a completed production POS.

Hosting research: free compute is not necessarily permanent hosting.
Back4app's own September 2026 engineering article says its free container
URL expires after 60 minutes, while Hugging Face Docker compute now requires
a paid subscription and Koyeb requires billing card verification. Northflank
pricing and legacy docs disagree on card requirements, so account onboarding
must be verified before choosing it. Do not silently introduce any cost.
