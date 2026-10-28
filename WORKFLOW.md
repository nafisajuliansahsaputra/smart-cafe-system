# WORKFLOW.md — Development and Release Process

## 1. Operating model

This repository is currently documentation-first. Use small vertical slices that bring together DB, backend, staff/customer UI and tests. Avoid building all screens with fake data and only then attempting real operations.

Standard path:

~~~text
select TODO item -> read requirements -> classify risk
 -> branch -> domain/DB/API contract -> implementation
 -> unit + integration + negative tests -> UI
 -> E2E -> security review -> docs/TODO -> PR -> review -> merge
~~~

No automatic unattended run is configured by this document.

## 2. Task intake

For each task record: milestone, user/actor, route, state transition, permissions, data invariants, failure behavior, tests, UI impact and rollout considerations. Classify as product, domain, API, DB, UI, provider, security or operations.

If feature needs a policy decision listed in PRD open decisions, create ADR first.

## 3. Branches and commits

- feat/table-sessions
- feat/order-control-center
- feat/kds-ticket-queue
- feat/cash-approval
- feat/midtrans-sandbox
- feat/game-loans
- feat/receipt-delivery
- security/guest-session-scope
- fix/double-submit
- docs/receipt-policy

Conventional commits: feat:, fix:, docs:, test:, refactor:, security:, ci:, chore:. Main remains reviewable, buildable when code exists.

## 4. Domain implementation order

1. Confirm requirements and state machine.
2. Design data model, indexes, and DB invariants.
3. Create Flyway migration.
4. Implement server-side validation and per-actor authorization.
5. Add domain services and transaction boundaries.
6. Add idempotency, audit and outbox where relevant.
7. Implement API + OpenAPI errors/responses.
8. Implement staff/customer UI with actual API (no secret logic in client).
9. Test happy, failed, unauthorized, duplicate and concurrency paths.
10. Update milestone status + docs and demo steps.

Never hold database locks while awaiting gateway or receipt network I/O.

## 5. OCC/KDS cash workflow

Order Control Center must show cash orders awaiting approval, pending online payments, ready-to-serve food, unresolved payments, canceled orders and operational exceptions.

- Guest submits cash order -> authorized waiter/cashier verifies visit and approves -> kitchen ticket -> ready -> waiter serves and collects cash -> backend records tender and marks paid -> receipt finalizes.
- Inability to collect cash is an incident/unresolved debt, not a reason to fabricate paid status.
- A waiter can only mark a serving task complete when permitted; cashier/manager can investigate and reconcile. Every override is audited.

## 6. Provider workflow

- Start with fake gateway for deterministic tests, then activate Midtrans sandbox keys stored only in secret manager/environment (never Git).
- Validate documented notification signature and status response; correlate merchant order ID, amount and environment.
- Persist provider event identity; ignore already-applied transitions.
- Handle pending/expiry/late success/retry and reconciliation via status API.
- Confirm sandbox API options before selecting QRIS/VA UX.
- Live merchant activation and production use are separate review gates.

Receipt delivery:
- First produce in-session/downloadable receipt for PAID orders.
- Introduce provider adapter only after approved channel/opt-in policy.
- Use immutable receipt + outbox job, deduplication and status tracking.
- Failed message does not modify payment/order truth.
- Do not send real messages in tests; use fake recipient and provider.

## 7. Tests by stage

**Fast loop:** Java unit tests (state, money, permissions), frontend unit/typecheck.

**DB/integration:** PostgreSQL Testcontainers, Flyway reset, unique/locking/race tests, Spring Security deny cases, fake provider and webhook.

**E2E:** customer QR join -> cash order -> OCC approve -> KDS prepare -> waiter serve/collect -> receipt -> return game -> close; digital sandbox scenarios after integration.

**Performance:** measure, do not invent latency or throughput. Include staff SSE reconnect/read-model consistency tests.

**Security gate:** secret scan, dependency scan, SAST, authorization regression and webhook tampering.

## 8. Pull request checklist

- What and why; milestone ID; design/architecture impact.
- Migration safety and rollback/forward-fix strategy.
- Role/guest access boundaries tested.
- Price and payment integrity unaffected or covered.
- No PII/token logging and no third-party calls from transaction.
- Screenshot/video only when UI touched; no unrelated layout regression.
- Tests run with results and any known remaining blockers.
- TODO and docs updated accurately.
- Owner approval before protected or production actions.

## 9. CI/CD phases

Phase A: on PR, build/lint/typecheck, unit, integration DB and secret scanning.

Phase B: staging, isolated sandbox credentials, migration, smoke/E2E, provider simulator tests.

Phase C: guarded production deploy with approvals, backup verification, health/readiness and rollback plan.

Do not include live provider secrets or personal data in CI logs.

## 10. Production readiness

Required before pilot:
- operator accounts and least-privilege roles;
- HTTPS, cookie security, origin/CSRF checks;
- database backup + restore rehearsal;
- gateway event reconciliation and operational alerting;
- manual override/incident guide for unpaid orders, lost games, failed receipts;
- documented migration rollout and rollback;
- monitoring of checkout, payments, outbox, KDS queue and order lag.

## 11. Incident response

1. Detect/contain and protect financial correctness.
2. Disable unsafe feature/provider action when possible.
3. Inspect structured logs/audit IDs without leaking PII.
4. Reconcile gateway and DB state before any financial manual correction.
5. Restore service, replay idempotent outbox jobs, verify session/task consistency.
6. Write incident report and regression tests.

Never delete financial history to make the dashboard look clean.

## 12. Documentation lifecycle

- PRD for feature/scope/role decision.
- Architecture for module/boundary changes.
- docs/STATE-MACHINES.md for transition rules.
- docs/DATA-MODEL.md for persistence/constraints.
- docs/API-CONTRACT.md for external/internal contracts.
- docs/RECEIPTS.md for consent/delivery.
- docs/decisions for new ADRs.
- TODO for verified implementation reality.

Do not claim a service deployed, tested, or production-ready solely because the documentation exists.
