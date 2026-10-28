# Testing Strategy — Smart Cafe System

**Status:** Required verification plan; not yet executed. No current build, test, pipeline or deployment exists.

## 1. Testing pyramid

- **Domain/unit (fast):** calculations, table/session policies, order/payment/game state transitions, authorization decisions, receipt logic.
- **Integration (real PostgreSQL):** Flyway migration, schema constraints, locks, idempotency, transaction boundaries, outbox. Use Testcontainers; no in-memory DB as sole source of truth.
- **API/security:** Spring Security allow/deny matrix, invalid input, rate-limit tests, provider webhook signature/amount checks, OpenAPI contract verification.
- **Frontend:** accessibility, formatting, validation, checkout and OCC state rendering; typecheck/lint.
- **End-to-end:** complete customer -> OCC -> kitchen -> waiter -> receipt; fake gateway and sandbox scenarios separately.
- **Resilience:** gateway timeout, duplicate events, SSE disconnect/reconnect, receipt outage, worker restart.
- **Operational:** load/performance after baseline, backup restore, release rollback rehearsal.

## 2. Seed fixtures

Use synthetic names and fake non-deliverable phones; test data must never cause external messages or live payments.

Fixtures:
- Tables 01, 02, 07 with only one active visit each.
- Guests A/B on same table with isolated carts; guest C on another table.
- Roles waiter, kitchen, cashier, admin, plus disabled staff.
- Menu: latte, chicken rice, variants; limited stock item with only one unit.
- Games: UNO with copies UNO-001/002; Jenga with one active loan.
- Digital payment attempts: PENDING, SUCCEEDED, FAILED, EXPIRED, late success.
- Fake receipt sender: sent, delayed, throttled, temporary failure, permanently denied.

## 3. Critical acceptance matrix

| Case | Expected |
| --- | --- |
| Scan static QR without valid join proof | No privileged checkout |
| Closed visit reuses old guest credential | 401/409; no new order |
| Guest A requests Guest B order | deny and no data leak |
| Two simultaneous checkouts same key | one order |
| Two checkouts same key, different payload | conflict |
| Menu price changed after submission | old order retains snapshots |
| Last stock simultaneous attempts | at most one allocation |
| Cash approved for kitchen | order ACCEPTED, payment AWAITING_CASH |
| Cash tender captured twice | one settled payment event |
| Kitchen tries to mark PAID | 403 |
| Digital webhook forged signature | no payment change |
| Digital webhook correct signature, wrong amount | no payment change; alert/reconcile |
| Duplicate genuine success event | one paid transition, one ticket, one receipt |
| FAILED after already PAID | never regress PAID |
| Late success after expired/cancelled attempt | reconciliation case, not silently discard |
| Two game requests for last copy | one assigned only |
| Game returned damaged | not AVAILABLE; recorded exception |
| Visit close with live game/unpaid cash | blocked or audited authorized override |
| Receipt channel not opted-in | no message, in-app receipt still available |
| Receipt provider temporary failure | payment unaffected, durable retries |
| Receipt link expired | no receipt disclosure |
| SSE disconnect | full canonical snapshot on reconnect |
| Disabled staff token | cannot continue protected action |

## 4. Payment testing gates

- Tests of provider signature and tamper checks with safe fixtures.
- Sandboxed QRIS/VA create, pending, settle, expire/abort, retry, notification replay.
- Reconciling missing notification via status API; compare amount, order ID, merchant and environment.
- Provider error/outage never leaves unaccounted paid money.
- All status changes auditable and correlated to one payment intent.

Midtrans official docs:
- https://docs.midtrans.com/docs/https-notification-webhooks
- https://docs.midtrans.com/reference/handle-notifications
- https://docs.midtrans.com/reference/testing-bi-snap-on-sandbox-environment

Choose one API product at implementation time (Snap or Core API); do not mix unrelated endpoint contracts.

## 5. CI stages (to be implemented)

PR gates:
1. Deterministic dependency install and source formatting.
2. Java compilation, static analysis, unit tests.
3. Testcontainers PostgreSQL integration + migrations from scratch.
4. TypeScript typecheck, lint, frontend tests/build.
5. Secret scan, dependency and SAST checks.
6. Optional browser E2E for affected journeys.

Staging:
- Environment isolation and sandbox credentials.
- End-to-end tests for cash and digital transactions.
- Auth role tests and failure drills.
- Startup health/readiness and migration safety.

Release:
- No failing critical test, unresolved payment integrity issue, credential leak, or untested DB change.
- Demonstrated backup/restore and rollback.

## 6. Evidence and metrics

Every completed TODO item links to PR, test command/output or repeatable manual procedure. Record actual measured response times, p95, number of concurrent guests, failures and environment. Avoid invented performance claims.

Monitor at minimum:
- request latency/error ratio;
- orders awaiting cash approval;
- KDS pending/ready age;
- payment pending/reconciliation cases;
- receipt outbox retries/failures;
- game loans overdue/outstanding;
- SSE reconnect rate.

## 7. Test environment safety

- Fake payment and receipt adapters by default.
- Midtrans SANDBOX keys only in appropriate environment.
- No real merchant order, phone, cash tender or PII in CI.
- Demo data isolated/resettable, clear visible "DEMO" labels.
- Automated tests never trigger external charge or real customer message.

## 8. Verified M2 API development coverage (2026-10-29)

[GitHub Actions run 40744023293](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40744023293) passed **22 Java tests**, zero failures/errors/skipped, including PostgreSQL 17 Testcontainers with Flyway V1–V4; frontend lint/typecheck/build and clean-start Docker Compose communication also succeeded.

The tested cash API route covers verified guest proof, idempotent cash checkout, price snapshot integrity, staff OCC approval with payment still awaiting cash, kitchen start/ready, waiter serve, recorded tender and receipt, same-key replay, cross-guest receipt denial, blocked visit closure with unpaid cash and session revocation after valid closure. Concurrent same-key order submissions are tested.

These are backend integration tests, **not browser-level E2E, performance, penetration testing, real cash collection validation, provider sandbox transactions or production certification**.

## 9. Follow-up M2 hardening evidence

[CI run 40744820730](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40744820730) passed 23 Java tests including parallel cash tender attempts with distinct idempotency keys and exactly one settlement. [CI run 40744971944](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40744971944) passed the V5 database rejection test for prepayment receipts. The final head CI must additionally pass waiter ownership denials.
