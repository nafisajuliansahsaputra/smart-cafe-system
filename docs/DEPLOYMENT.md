# Deployment and Operations Plan

**Status:** Planned approach; nothing deployed by this documentation. Never treat example paths as working commands until app scaffold exists.

## 1. Environment tiers

**Local:** Next.js + Spring Boot + PostgreSQL via Docker Compose; fake payment/receipt adapter, test fixtures. No public ingress needed.

**CI:** isolated ephemeral database, unit/integration/lint/build/security verification. Testcontainers or CI database available and deterministically created.

**Staging:** protected public URL, isolated DB, payment SANDBOX only, mock message sender unless explicitly approved sandbox, synthetic data, E2E test accounts.

**Production pilot:** one cafe with contractual/merchant clearance, HTTPS, approved messaging, data handling/privacy notice, access controls, backups, monitoring and manual operating procedures.

Never point preview/staging at production DB or live merchant payment keys.

## 2. Proposed infrastructure

~~~text
Internet -> TLS reverse proxy -> Next.js web
                            -> Spring Boot API
                             | Postgres (private network)
                             | outbox worker (same codebase initially)
                             | gateway / sender egress
Metrics & logs (restricted) <- applications
~~~

- Separate private DB network; never expose Postgres to public internet.
- Host secrets in environment/secret manager, not committed or baked into client bundles.
- Plan volumes, DB backups and off-site copies.
- Worker processes outbox with lease/lock and idempotent retries.
- Reverse proxy protects upload/body sizes, HTTPS redirects, request timeouts and secure headers.

## 3. CI/CD process

1. PR runs quality/security/migration gates.
2. Merge to main after review.
3. Build immutable Docker images tagged with Git SHA.
4. Deploy staging with proper secrets and migrate safely.
5. Run smoke/E2E and observe.
6. Require explicit production approval for pilot.
7. Deploy immutable image; run post-deployment health checks.
8. Roll back application if needed, but review schema compatibility before rollback.

Do not configure unattended deployment or purchase cloud resources without owner authorization.

## 4. Migration discipline

- Versioned Flyway migrations committed in source.
- Test clean database initialization and upgrade from last deployed version.
- For destructive changes use expand/backfill/contract.
- Take backups when appropriate, have verified restore strategy.
- Never assume rolling back code will reverse destructive DB migration.

## 5. Health & monitoring

Health:
- liveness: process responsive;
- readiness: necessary DB connection and startup migrations complete;
- downstream dependency status shown separately so a receipt provider failure does not unnecessarily stop all order traffic.

Metrics:
- active table visits and order throughput;
- unresolved cash approvals and uncollected served orders;
- KDS preparation and delivery lag;
- pending gateway attempts, duplicate/invalid webhook counts, reconciliation backlog;
- receipt queued/failed and worker lag;
- active/reserved game copies and orphan reservations;
- HTTP error/latency and DB pool saturation.

Alert on financial contradictions, persistent outbox failure and runaway pending kitchen queue. Avoid PII in monitoring labels.

## 6. Backup and restore

Document RPO/RTO only after actual measurements and operational policy. Validate:
- consistent DB backups and retention;
- encrypted storage and restricted restore;
- restoring to separate environment;
- reconciliation of external payments after data restore;
- worker replay with idempotency; never re-send receipts blindly.

## 7. Operational runbooks (to add with code)

- Cafe internet loss / kitchen screen offline.
- Provider payment outage or delayed notification.
- Cash collected but staff forgot to record.
- Paid order unavailable/stock discrepancy.
- Receipt sender outage.
- Game missing/damaged and outstanding visit.
- Wrong table joined or QR copied.
- Refund dispute and audit investigation.
- Database backup restore and incident notification.

Offline taking of new payments/orders is **not** implicitly supported by the web architecture. If later required, design a dedicated offline-first protocol and reconciliation before promising it.

## 8. Release gates

Before real guests or money:
- complete protected cashier OCC and KDS;
- staff role deny tests;
- real payment provider security review/merchant activation;
- in-person cash receipt and audit process;
- local privacy policy/legal review;
- tested migrations, backup/restore and monitoring;
- incident escalation and reconciliation playbook;
- no real messaging until approved provider/consent/template conditions met.

Without these, market the project as an engineering demo using sandbox data, not a live cafe POS.

## 9. Local-only HTTPS edge acceptance (draft PR #10)

The repository now contains an **opt-in, loopback-only** HTTPS Nginx Compose overlay for CI/browser security verification: [docs/M2-HTTPS-EDGE-ACCEPTANCE.md](M2-HTTPS-EDGE-ACCEPTANCE.md). It validates the trusted-Host, forwarded-header and POST rate-limit boundary on a disposable `https://localhost:8443` using a self-signed cert. This is **not** a production-ready reverse proxy. A public deployment would require owned DNS, trusted certificate issuance/renewal, real upstream proxy/CDN trust and rate limiting, CSP/HSTS, backups and authenticated staging sign-off. Nothing is deployed by this PR.


## 10. Gated PostgreSQL backup and restore rehearsal (stacked draft PR #33)

The clean-start Docker CI job now seeds a **disposable, synthetic**
staff/game/menu/audit fixture and performs a real PostgreSQL custom
archive `pg_dump -Fc` while the API is online. It verifies the dump
contains physical inventory data, creates a **new separate** PostgreSQL
database, restores using `pg_restore --exit-on-error`, then checks
all public-table counts, index and constraint definitions, selected
deterministic content fingerprints for staff, roles, game copies,
menu items, audit records and Flyway migration history. It separately
verifies the synthetic manager audit event still links to the correct
physical game unit and actor.

`infra/verification/restore-rehearsal.sh` is explicitly gated:
`CI=true`, `SCS_RESTORE_TEST_ISOLATED=yes`, original DB name exactly
`smart_cafe` and recognized disposable fixtures are all required.
It **refuses an existing destination DB**, never drops the source
and removes only the temporary dump and the destination database it
created. The synthetic fixture is inserted by CI independently,
*after* smoke tests pass, never by the backup/restore script itself.

This is a **single controlled CI restore rehearsal**, not an approved
production backup solution. Outstanding pilot gates: encrypted offsite
storage, access-controlled backup retention, staging restore on
production-like hosting, PITR/WAL planning, measured recovery time and
data loss windows, incident response, and real payment reconciliation.
Never apply the disposable E2E fixtures to a real cafe database.
