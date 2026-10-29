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

## 10. M2 cart and finite-stock integration scenarios

`CartAndStockIntegrationTest` covers separate guest carts, unauthorized access, optimistic version conflicts, persisted cart checkout/replay after consumption, immutable order pricing, two concurrent guests competing for the last available unit, and rollback of any prior stock mutations when one line is sold out. Manager stock editing tests include denied CASHIER access, audit, optimistic version mismatch, and hiding out-of-stock entries. `CashOrderFlowIntegrationTest` additionally covers cashier rejection, single-release inventory restoration and visit closure after rejection.

Verify from the current [PR #2 CI checks](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/2/checks). Do not mark this test set passed on a new commit until its exact CI run succeeds.

### M2 additional status privacy

`CashOrderFlowIntegrationTest` checks that a guest can retrieve their own order details with correct cash status while another guest is rejected with a safe 404. New cash checkout creates an `ORDER_SUBMITTED` outbox entry in the same transaction; SSE delivery is not yet implemented.

## 11. Menu catalog and options

`CartAndStockIntegrationTest` includes manager-only category/item create/update, initial HIDDEN visibility, stale revision conflicts, disabled category hiding, required variant group setup, invalid/missing option rejection, authoritative price delta in cart/checkout, immutable old price and label after manager option edits, stock decrement, and idempotent replay. Results are tied to [PR #2 checks](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/2/checks), not assumed until the exact head CI succeeds.

## 12. V9 modifier regression scenarios

Synthetic PostgreSQL Testcontainers coverage includes: creating independent Size/Milk/Extras groups; missing a required group; selecting two options from one group; multi-group cart pricing, stock allocation and immutable order snapshot array; idempotent replay with reversed modifier input order; publication of all groups; rejection by the legacy singular lookup when more than one active group exists; and archived options returning a safe unorderable cart preview and a rejected checkout without spending stock. **Inspect the latest PR-head CI** before treating these added cases as passing.

## M2 guest join throttle integration (draft PR #7)

A new TableVisitIntegrationTest case sends 24 malformed-proof-but-well-formed JSON join attempts with deliberately spoofed `X-Forwarded-For` headers and a constant actual remote address. The 25th is denied with 429/RATE_LIMITED/Retry-After/no-store and no session created, confirming counters remain committed even when proof authentication fails. A different socket peer may legitimately join the same active visit, and the blocked peer may join another active table (within remaining global peer quota).

Test environments provide a synthetic `SCS_JOIN_THROTTLE_SECRET`; Compose CI provides a separate CI-only value and runs Flyway V10. The limit applies to valid JSON that passes controller validation: malformed requests must also be guarded at a trusted ingress layer. Verify all Java/DB/Next.js/Compose jobs on the final PR SHA before approving.

## Browser E2E acceptance on draft PR #8

A dedicated GitHub Actions Chromium job starts an ephemeral Docker Compose web+API+PostgreSQL installation, waits for health, runs Flyway, injects `infra/fixtures/e2e-browser.sql` and uses a transient Playwright test dependency. **Never apply that SQL fixture to a real or persistent database.** Fixture roles and passwords are intentionally synthetic and not production credentials.

The suite verifies navigation and real server actions with separate waiter, cashier and kitchen accounts plus two guest browser contexts. It checks role route denial, transient table join proof, required+optional modifiers and authoritative IDR subtotal, order AWAITING_CASH before tender, staff OCC approve, kitchen prepare/ready, waiter handover and cash/change, own paid receipt, cross-guest 404, and closing the visit revoking guest access. Browser token safety checks require HttpOnly, SameSite-Strict, path-scoped cookie with no token rendered in HTML.

CI retains screenshots and Playwright trace on failures for three days and destroys its database volume afterward. Exact-head green status, not merely existence of a test, is required for any completion claims. No real cafe, phone number, funds or external messages involved.


## M2 principal / Origin / negative Chromium acceptance (draft PR #9)

Existing Java TableVisitIntegrationTest now uses a valid GUEST token to call both staff-authenticated `/me` and `/logout`, expecting 401 because GuestBearerFilter excludes staff-auth routes; it then proves the original guest token still works. This guards against principal type confusion and accidental guest revocation.

`apps/web/e2e/security-boundaries.spec.mjs` adds real Chromium/Next.js Server Action negative tests: foreign-Origin request with authenticated staff cookies cannot invoke logout; a 64KB synthetic form is rejected by the 32KB Server Action limit; closed public QR does not render a join form or issue a guest credential. `cash-cycle.spec.mjs` attempts to close an active table *before* tender and verifies backend refuses; after cash is paid it closes successfully. Run CI in ephemeral Compose only and retain the completed exact-head GitHub Actions run as evidence. This cannot replace a real HTTPS reverse-proxy Origin/Host assessment.

## HTTPS reverse-proxy integration (draft PR #10)

Separate CI job `edge-https-e2e` uses a throwaway 1-day self-signed localhost certificate, the Compose Nginx overlay and an ephemeral PostgreSQL/Next.js/Spring Boot stack. It verifies expected local HTTPS responses, Host 421 denial, ingress 429 across forged X-Forwarded-For values, then seeds synthetic fixtures and executes Playwright Chromium functional/security acceptance **through the edge**, not localhost:3000. A dedicated browser case injects hostile X-Forwarded-Host and Proto into a legitimate login and verifies a Secure/HttpOnly/Strict cookie. No real funds or private records; CI destroys its synthetic database and local key. See [docs/M2-HTTPS-EDGE-ACCEPTANCE.md](docs/M2-HTTPS-EDGE-ACCEPTANCE.md).

## Live SSE browser acceptance (draft PR #11)

Chromium retains the OCC view before guest checkout, KDS before cashier approval, waiter dashboard before kitchen READY, and guest detail through PAID, checking the receiving page changes without navigation. Foreign-owned guest order streams return 404 and malformed UUIDs return 400. Both HTTP and local HTTPS proxy suites run the cash lifecycle. These tests do not certify production capacity.
