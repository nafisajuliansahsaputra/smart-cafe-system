# TODO.md — Smart Cafe System Execution Roadmap

**Status ledger as of 2026-10-29.** Checkboxes reflect verified repository work, not aspirations. Document-only initialization does **not** mean the application exists.

## M0 — Product and contract foundation

- [x] Create GitHub repository smart-cafe-system (owner-created).
- [x] Initialize README and canonical PRD.
- [x] Define architecture baseline and agent/workflow rules.
- [x] Document table/guest, order, payment, physical-game and receipt contracts.
- [x] Define Order Control Center as mandatory staff surface.
- [x] Write initial testing/security/deployment strategy and ADRs.
- [ ] Decide project license.
- [ ] Confirm cash tender policy, receipt messaging provider and game fee rules with owner before implementation dependent on them.

## M1 — Executable skeleton (NOT STARTED)

- [ ] Create apps/web Next.js TypeScript application with route groups and design foundation.
- [ ] Create backend Spring Boot Java application with modular package boundaries.
- [ ] Add Docker Compose PostgreSQL and development environment template.
- [ ] Add Flyway baseline migrations, DB constraints, example fake fixtures.
- [ ] Add API OpenAPI generation, error envelope and health endpoints.
- [ ] Add local formatting, static checks, unit/integration test commands and PR CI.
- [ ] Prove clean clone -> local startup -> migration -> tests.

## M2 — Cash ordering vertical slice (NOT STARTED)

- [ ] Staff login with roles and session revocation.
- [ ] Table management, alias creation and visit open/close.
- [ ] QR URL table entry, guest join proof and scoped session.
- [ ] Catalog, categories, variants and availability.
- [ ] Guest cart, server pricing and idempotent checkout.
- [ ] Cash order and staff approval lifecycle with audit.
- [ ] **Order Control Center** queue/search/detail/approve/exception tools.
- [ ] Kitchen display admission and preparation states.
- [ ] Waiter delivery and verified cash tender capture.
- [ ] Guest status + in-session finalized receipt.
- [ ] E2E cash happy path, unauthorized cases, duplicate checkout and closure rules.

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
