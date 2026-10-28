# TODO.md — Smart Cafe System Execution Roadmap

**Status ledger as of 2026-10-29; M1 scaffold verified via CI on a development branch, pending review/merge.** Checkboxes reflect verified repository work, not aspirations. Document-only initialization does **not** mean the application exists.

## M0 — Product and contract foundation

- [x] Create GitHub repository smart-cafe-system (owner-created).
- [x] Initialize README and canonical PRD.
- [x] Define architecture baseline and agent/workflow rules.
- [x] Document table/guest, order, payment, physical-game and receipt contracts.
- [x] Define Order Control Center as mandatory staff surface.
- [x] Write initial testing/security/deployment strategy and ADRs.
- [ ] Decide project license.
- [ ] Confirm cash tender policy, receipt messaging provider and game fee rules with owner before implementation dependent on them.

## M1 — Executable skeleton (CI VERIFIED ON FEATURE BRANCH)

- [x] Create apps/web Next.js TypeScript application with route groups and design foundation.
- [x] Create backend Spring Boot Java application with modular package boundaries.
- [x] Add Docker Compose PostgreSQL and development environment template.
- [x] Add Flyway baseline migrations, DB constraints, example fake fixtures.
- [x] Add API OpenAPI generation, error envelope and health endpoints.
- [x] Add local formatting, static checks, unit/integration test commands and PR CI.
- [x] Prove clean clone -> local startup -> migration -> tests.

M1 evidence: [CI run 40739664242](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40739664242) passed Java/JUnit, Testcontainers/PostgreSQL/Flyway V1, Next.js lint/typecheck/build with npm ci, and clean-clone Docker Compose + OpenAPI + security deny + web -> API -> DB smoke. [Verification record](docs/M1-VERIFICATION.md). All checkmarks apply to the feature branch only; no merge, deployment, or business feature is implied.

## M2 — Cash ordering vertical slice (BACKEND FUNCTIONAL; FULL UI IN PROGRESS)

- [x] Staff login API with roles and session revocation (PostgreSQL/MockMvc verified; staff browser login UI pending).
- [ ] Table management, alias creation and visit open/close.
- [ ] QR URL table entry, guest join proof and scoped session.
- [ ] Catalog, categories, variants and availability.
- [x] Guest cart, server pricing and idempotent checkout (PostgreSQL cart v6, atomic limited stock, idempotency replay and race tests in CI).
- [x] Cash order submission and cashier approval lifecycle with audit (API/DB verified; OCC staff UI still pending).
- [ ] **Order Control Center** queue/search/detail/approve/exception tools.
- [ ] Kitchen display admission and preparation states.
- [ ] Waiter delivery and verified cash tender capture.
- [ ] Guest status + in-session finalized receipt.
- [ ] E2E cash happy path, unauthorized cases, duplicate checkout and closure rules.

M2 progress: server-authoritative guest cash checkout with idempotency and immutable snapshots; staff auth and guest visit scoping; backend OCC/KDS/waiter lifecycle, audited tender and paid-only receipt. API integration tests passed 23/23 with concurrent cash tender coverage on [CI run 40744820730](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40744023293). Staff/guest operational browser UIs, variants and ingredients, cashier exception/reconciliation workflows, payment adapters, production hardening and full browser E2E remain incomplete. See [M2 engineering record](docs/M2-CASH-DOMAIN.md).

## M3 — Reliability and payment sandbox (NOT STARTED)

- [ ] SSE updates + reconnect/snapshot recovery for OCC, KDS and guest.
- [ ] Abstract PaymentGateway with deterministic fake adapter.
- [ ] Midtrans sandbox onboarding and test credentials via environment (no keys in Git).
- [ ] Dynamic QRIS and VA transaction attempts.
- [ ] Verified webhook signature/amount/order and transaction state mapping.
- [ ] Dedupe, late-success reconciliation, retry/expiry, refunds policy.
- [ ] Financial audit, cashier reconciliation and payment E2E scenarios.

## M4 — Physical-game inventory and loans (NOT STARTED)

- [ ] Game catalog and serialized copies, availability status.
- [ ] Guest game request flow during visit/checkout.
- [ ] Atomic allocation/expiry of reservation.
- [ ] Waiter delivery/handover and return/inspection.
- [ ] Damage/loss exception and manager override.
- [ ] Concurrency test: one copy, two guests.
- [ ] Closure test with outstanding game.

## M5 — Receipt messaging and operational management (NOT STARTED)

- [ ] Phone collection, normalization, purpose notice and opt-in UI.
- [ ] Immutable finalized receipts and secure access/download.
- [ ] Durable outbox and receipt dispatch state.
- [ ] Select messaging provider/approve template/cost before external sends.
- [ ] Sandbox/fake provider tests for send, retry, idempotency and opt-out.
- [ ] Configure minimum audit/reporting and basic daily operations views.
- [ ] Staff account, menu, table and game management admin tools.

## M6 — Production engineering and portfolio (NOT STARTED)

- [ ] CI scans: secrets, dependency vulnerabilities and SAST.
- [ ] Reproducible Linux/Docker deployment with guarded environment config.
- [ ] Logs, Actuator/metrics, alerts for payments, kitchen latency, receipt failures.
- [ ] Backup and restore rehearsal with measured recovery time.
- [ ] Load/concurrency tests and fixes based on measurements.
- [ ] Accessibility and mobile QA.
- [ ] Security review for auth, QR replay, payment webhooks and PII.
- [ ] Demo seed data, isolated demo accounts and reset workflow.
- [ ] End-to-end video, architecture walkthrough and portfolio case study.
- [ ] Pilot checklist before accepting real money or real customer phone data.

## Maintenance rule

Only mark checked when code/docs exist on the target branch **and** the item's acceptance tests/evidence exist. List blockers in relevant PR/issue, do not add optimistic completion claims. Update this file together with changes.

### M2 inventory follow-up

V6 provides guest cart and limited menu-item quantities; V7 tracks per-order stock allocations so rejection can release stock once. Role-guarded manager stock adjustments and cashier pre-kitchen rejection are implemented and tested on this feature branch. This is **not** ingredient-level or expiry-aware inventory. See [cart and stock controls](docs/M2-CART-INVENTORY.md).

### Catalog/backend follow-up

Manager/admin category and item API writes with audit and revision checks have been introduced. Flyway V8 adds single-choice variant groups/options and a selected option on cart lines. Server-priced variant selection and immutable order snapshots are covered by tests in the [PR #2 checks](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/2/checks). The original M2 catalog checkbox remains open because multi-group modifiers, catalog browser management and other product rules are not complete. See [M2 catalog variants](docs/M2-CATALOG-VARIANTS.md).
