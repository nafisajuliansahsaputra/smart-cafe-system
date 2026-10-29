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

M2 progress: server-authoritative guest cash checkout with idempotency and immutable snapshots; staff auth and guest visit scoping; backend OCC/KDS/waiter lifecycle, audited tender and paid-only receipt. Backend integration tests passed 30/30 on [CI run 40749892835](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40749892835); further modifier tests require PR-head CI verification. Staff/guest operational browser UIs, ingredient inventory, cashier exception/reconciliation workflows, payment adapters, production hardening and full browser E2E remain incomplete. See [M2 engineering record](docs/M2-CASH-DOMAIN.md).

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

### M2 modifier groups V9

V9 removes the old one-group-per-item limit and introduces normalized `cart_line_variant_options` while preserving the old `variantOptionId` client input for a single choice. The new `variantOptionIds` input accepts one choice per active group, with all required groups present (maximum eight groups). The backend canonicalizes options for idempotency, computes all deltas in checked integer IDR, and stores the combined label and immutable options array in the order snapshot. Public `/menu/{item}/variant-groups` lists all active groups. The singular legacy lookup refuses a multi-group item instead of omitting required choices. Multi-group options do **not** imply arbitrary topping quantities, per-option stock, or multiple differently customized cart lines for the same menu item. M2 catalog remains open until browser management and remaining rules pass full acceptance.


### M2 staff browser follow-up (stacked PR #3)

A first staff browser implementation is proposed on `feat/m2-staff-oc-browser`: server-side staff auth bridge (HttpOnly SameSite=Strict cookie), role-gated staff homepage, server-rendered OCC queue/filter/detail, and server-only approve/reject actions. Backend still performs authorization and transactional state transitions. The UI is **read/snapshot on reload**, not realtime. Staff sessions, cash lifecycle and rejection correctness are provided by PR #2.

Keep the OCC and M2 full-flow checkboxes unchecked until the stacked PR passes final-head CI plus authenticated operator E2E, denial/CSRF checks, and full waiter/kitchen/receipt coverage. Manual browser acceptance is recorded in [docs/M2-STAFF-BROWSER.md](docs/M2-STAFF-BROWSER.md).


### M2 kitchen/waiter browser follow-up (stacked PR #4)

A new backend waiter tasks projection restricts regular waiters to their own table visits; supervisor roles can read all. The first operational `/staff/kitchen` and `/staff/waiter` screens now submit authenticated, server-authoritative start/ready/served/cash-tender commands via Next.js Server Actions, with explicit physical-handover and physical-cash confirmations. Cash amounts and recorded change are returned from database projections rather than frontend estimates.

The M2 KDS/waiter/full cash-E2E checkboxes remain open until authenticated browser E2E, accessibility, deployed-origin security checks, table management UX and closed-visit cases pass. This PR adds no provider connection or production money handling. See [docs/M2-FULFILLMENT-UI.md](docs/M2-FULFILLMENT-UI.md).
