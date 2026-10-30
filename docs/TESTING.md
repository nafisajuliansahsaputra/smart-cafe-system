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

## Durable live outbox relay integration (draft PR #12)

V11 introduces distinct live publication state. New PostgreSQL Testcontainers integration checks batch publication, two monotonically increasing live event IDs, exactly one durable signal per original outbox ID, safe retry after reset without duplicate, and no mutation of business-outbox `processed_at`. An invalid synthetic event fails transactionally and remains pending with no corresponding live signal. Scheduled worker disabled by default in tests; Docker Compose enables it. The existing full browser HTTP/HTTPS cash flow must continue to pass. This is **not** a browser test of NOTIFY delivery or replay yet.

## M2 durable live cursor (draft PR #13)

A PostgreSQL Testcontainers/MockMvc regression exercises the new role/owner-scoped live cursor endpoint after actual checkout/approval/kitchen transitions and durable outbox publication. It checks foreign guest 404, guest-without-auth 401, cross-role 403, waiter not responsible for a visit cannot observe its event cursor, and previous cursor still detects a change after publication. Existing Chromium cash lifecycle now exercises order SSE using the real NOTIFY/cursor BFF bridge over HTTP and local HTTPS. No performance or production proxy capacity guarantee.

## M2 table live lifecycle (draft PR #14)

`TableVisitIntegrationTest.tableLifecycleEventsAreDurableAndProofRotationIsOwnerScoped` validates OPEN, ROTATE and CLOSE produce durable table signals, other waiters are not notified of another visit's rotation, supervisors are notified, and no proof appears in outbox JSON. Chromium cash-cycle acceptance keeps a **second cashier table-management browser tab** open before the waiter opens a table and expects live TERSEDIA → TERISI → TERSEDIA changes without navigating. Tests run over ordinary HTTP and the local-only TLS proxy. See docs/M2-TABLE-LIVE-EVENTS.md.

## Live subscriber admission and metrics (draft PR #15)

`LivePollCapacityTest` runs 20 concurrent virtual-thread admission attempts with deliberately constrained aggregate, guest and staff budgets. It asserts active polls never exceed capacity, all permits release, per-principal throttling does not starve another user and invalid limit configs fail startup. `OutboxLiveTelemetryTest` verifies aggregate-only gauges and that failed samples become -1/unknown. `OutboxLiveRelayIntegrationTest` now samples real PostgreSQL pending event counts before/after a synthetic durable relay dispatch. All prior 38+ Java domain tests and both ordinary HTTP/HTTPS Chromium business flows must remain green. These are correctness/race tests, NOT measured throughput or soak tests.

## Live-cursor async dispatch and synthetic load (draft PR #16)

MockMvc regression now uses `asyncDispatch` when `after` is provided, asserting that real deferred long-poll requests release the MVC request thread. A dedicated CI job seeds only synthetic staff in disposable PostgreSQL and performs simultaneous authenticated `after=0` requests, counts admission 200 versus 429, measures response p50/p95 and tests health responsiveness during active subscriptions. A second wave verifies an abusive single staff credential cannot exhaust all global slots. No test credentials/real PII are committed as runtime secrets. This is a short bounded synthetic stress gate, not a full production load/soak benchmark. See docs/M2-ASYNC-LIVE-LOAD.md.

## Two backend replicas / missed NOTIFY recovery (draft PR #17)

Independent GitHub Actions job `live-replica-recovery` starts two genuine API containers on 127.0.0.1:8080/8081 with one isolated PostgreSQL database, seeds only fake staff/table records, and drives actual authenticated waiter/table APIs. It proves both instances observe a committed table-open event via long-poll, stops replica B while A commits a join-proof rotation, restarts B and requires its initial durable cursor read to catch up, then checks B receives a subsequent table close. Anonymous access and kitchen's waiter-table cursor access are denied. No real funds/PII or public network are used. This is not a benchmark or certified multi-AZ failover.

## M2 cancelled long polls and browser offline catch-up (draft PR #18)

`LiveCursorAsyncBridgeTest` verifies a waiting virtual thread is interrupted when abandoned, duplicate cancellation is harmless, cancelled-before-start tasks do not run, no false result is delivered, and one staff session can reacquire its global/per-identity permit afterward. In the real Chromium synthetic cash lifecycle, KDS is taken offline immediately before cashier accepts an order. Once KDS network returns, the page must reconnect to SSE and fetch ACCEPTED without manually navigating or refreshing. Both HTTP and localhost HTTPS CI suites run this; all existing Java/Testcontainers, isolated concurrency and two-JVM recovery jobs remain required.

## M4 physical serialized game loans (stacked draft PR #19)

New PostgreSQL Testcontainers GameLoanIntegrationTest validates role gates, owner-only loan reads, duplicate active request, waiter visit scoping, no-copy assignment, wrong physical copy code, full REQUESTED→ASSIGNED→IN_USE→RETURNED flow, refusal to close an outstanding visit and successful close only after return or cancellation, audit, and two concurrent last-copy assignments (exactly one succeeds). Existing cash/order tests remain. Chromium synthetic cash flow now also requests the one seeded UNO demo copy, hands it over through waiter UI, proves PAID + SERVED alone cannot close, returns the unit, then closes. Both HTTP and localhost HTTPS suites should pass. No real merchant database or user funds involved.


## M4 scoped game SSE acceptance (stacked draft PR #20)

Testcontainers/MockMvc verifies V14 game outbox rows survive the relay,
increment unique durable cursors for the owning guest and responsible waiter,
stay invisible to another guest even at the same table and to a foreign
waiter, and deny kitchen access and malformed order-specific game cursor
requests. The ordinary HTTP and local HTTPS Chromium cash/game tests now
keep the waiter game page open when a guest requests UNO, assert the new
request becomes visible without manual refresh, and assert guest loan state
changes after physical handover. Verify the **exact PR head** against all
seven CI jobs; no prior branch's green run proves this commit. This is
change-only UI resynchronization, not offline borrowing, production mobile
acceptance, or an approved financial workflow.

## M4 damage quarantine and safe restoration (draft PR #22)

Testcontainers checks damaged-return proof (exact copy code), required
inspection reason, staff roles, active-copy guard, per-copy condition history,
damaged stock omitted from public availability, refusal to assign quarantined
stock, successful inspected manager restoration and active-copy re-quarantine
rejection. Separate test covers direct manager quarantine of an idle unit and
audit/reason preservation. Chromium synthetic cash/loan cycle now returns a
damaged demo unit, checks zero availability and restores it from a manager
session with no real funds or customer records. Existing cash closure and
HTTPS acceptance remain required on the exact head SHA.

## M4 manager condition history visibility (draft PR #23)

Manager/admin-only no-store endpoint and read-only dashboard show the latest
100 copy-condition events with before/after, reason, actor login, timestamp
and copy code, without guest or payment data. Testcontainers covers the
manager response, denied waiter/cashier reads and no guest ID leakage.
Chromium cash lifecycle verifies both damage and restoration notes appear
after a real staff action. Requires exact-head Java/browser CI.

## M4 catalogue inventory change wake-up (stacked draft PR #24)

V17 uses one shared durable game cursor for both owner-only loan events
and global-to-authenticated-viewer stock signals. Testcontainers
verifies new game/copy creation, assignment depletion, damaged return,
manager inspection/restoration, bounded public information, and that
foreign guests see inventory updates but never private loan requests.
Chromium HTTP/HTTPS keeps a guest games page open while the manager
restores the only damaged UNO and asserts the count updates without
navigating or manually refreshing. Existing two-replica and async
subscriber CI jobs still run.

## M4 manager unit search and pagination (draft PR #25)

Testcontainers seeds 53 distinct synthetic serialized copies and
asserts 25/25/3/0 pages, stable copy ordering and `hasNext`, filtered
DAMAGED/AVAILABLE results, denied anonymous/waiter/kitchen roles,
invalid condition/search/page errors, and literal wildcard/injection
queries that return no extra records. Chromium manager acceptance
searches for a missing unit and filters DAMAGED after restoring UNO,
then resets to see the registered unit; ordinary cash/game cycle
continues without public data or payment changes. Exact-head seven
CI jobs remain required before review.

## M4 condition audit history paging (stacked draft PR #26)

Testcontainers creates 53 timestamp-ordered synthetic inspection changes
on one serialized game unit and verifies database-driven pages
25/25/3/0, stable timestamp ordering, final status filter,
case-insensitive literal search, invalid inputs and anonymous/staff
role denial. No mutation or billing is involved in audit retrieval.
Chromium HTTP/HTTPS verifies manager search by inspection note,
filtering to a nonmatching after-condition, and restoring the full
history list while the existing guest/loan/cash cycle completes.
CI must be green for the exact pull request HEAD.

## M4 game loss report and review acceptance (draft PR #27)

PostgreSQL/Testcontainers validates wrong visit waiter, kitchen denied,
required report/review reasons, duplicated OPEN report, open-report
blocks physical return/table close, wrong manager serial and unsupported
decision rejected, explicit manager CONFIRM_LOST terminal loan and
unassignable LOST copy, guest sees neutral LOST state, audited decision
with empty-payload SSE, and table closure only after review. A second
case exercises manager DISMISS followed by a real-code verified return.
Chromium cash/receipt demo verifies an empty manager incident section
does not infer missing equipment or impose a charge. No merchant funds,
real guests or field device tests used.

Additional Chromium HTTP/HTTPS loss-disposition coverage: after the
synthetic manager restores a quarantined UNO deck, the guest borrows it
again; the waiter reports it missing while still ASSIGNED; the manager
receives the live incident, verifies the serial and confirms LOST; the
guest receives a neutral terminal status, unit inventory stays zero and
normal table closure follows only after signed review. No financial
operations are generated by the incident action.


## M4 LOST copy recovery acceptance (stacked draft PR #28)

Testcontainers covers unauthorized guest/waiter/cashier/kitchen, wrong
verified copy code, repeated recovery, invalid note, arbitrary
unverified LOST flags, preserved historical incident/loan and closed
visit, permanent linked inspection history, mandatory quarantine,
separate inspected restock, reuse after independent safe assignment,
safe outbox and unchanged financial state. Chromium HTTP/HTTPS repeats
the synthetic UNO manager-confirmed missing-unit scenario, then
recovers into DAMAGED quarantine, verifies zero available copies and
unchanged guest LOST status, separately restores AVAILABLE, checks
two inspection notes and keeps past incident CONFIRMED_LOST.


## M4 manager loss register paging and incident workload (draft PR #29)

Testcontainers seeds 104 uniquely identified synthetic reports (103
historically resolved + one OPEN) and confirms OPEN-first sort,
25/25/25/25/4/0 pages and hasNext, results beyond legacy first-100 window
via complete-database search, outcome filters, separately computed
openCount independent of page and query, invalid page/filter inputs,
case-insensitive safe literal search, wildcard/SQL-like input probes
and guest/waiter/cashier/kitchen access denial. Chromium HTTP/HTTPS
validates manager live OPEN count, missing-result search, mismatching
DISMISSED filter and reset before the manager confirms loss in the
normal synthetic cash/order/physical-game acceptance flow.


## M4 manager dashboard realtime loss alerts (stacked draft PR #30)

PostgreSQL/Testcontainers validates anonymous/invalid token 401,
waiter/cashier/kitchen 403, no-store manager success, four genuine
allocated-unit loss reports causing an OPEN count increase by four,
a **maximum three newest** minimal preview rows, strict omission of
reason, loan/guest/payment identifiers and incident text, and count
returning to baseline after four manager dismissals. Chromium HTTP
and HTTPS keep a manager landing tab open through the existing guest
cash/game E2E scenario and assert zero → one → zero unresolved alert
transitions after waiter report and manager review. Tab foregrounding
uses LiveRefresh's existing visibility recovery, not manual refresh.
Browser tests verify cashier/waiter never see the manager alert
and the link targets the OPEN-filtered manager queue.


## M4 attributed incident audit acceptance (draft PR #31)

PostgreSQL/Testcontainers validates manager-only evidence under
anonymous/guest/waiter/cashier/kitchen denial, invalid UUID and
missing-id handling, no-store delivery, original reporter and pending
reviewer null, request correlation ID and exactly one report audit,
then confirms manager loss and verifies separate manager actor,
review timestamp, audit event ordering, two persisted audit entries,
unchanged terminal LOST loan/copy and replay refusal. Response
must omit guest ID, contact, payment and generic audit diffs.
Chromium HTTP/HTTPS starts a real waiter incident, follows the
manager-only evidence link and verifies report/staff attribution
and pending decision, denies cashier/waiter page access, then reviews
and reopens timeline to verify final manager decision and staff
attribution while the existing full cash/game lifecycle remains intact.


## M4 mobile/tablet waiter and manager UI checks (stacked draft PR #32)

Chromium tests now include a separate mobile/touch-context suite at
320, 375, 390 and 768 CSS pixels. The waiter dashboard, waiter/cash
task view and physical-game page, plus manager dashboard/game review
at the same widths, are checked for document-level and panel-level
horizontal overflow and undersized link/button/confirmation tap
targets. The suite checks keyboard focus visibility, accessible
incident section labeling and direct hash-anchored search, reset and
filter navigation. The full synthetic cash/game/incident E2E also
switches the manager's attributed audit evidence page to 320px,
verifying the chronological event timeline, machine-readable time
and no overflow around long audit IDs, before resuming desktop
operations. Runs on both HTTP and trusted HTTPS proxy jobs.
No production or device-specific behavior is inferred from a
headless Chromium-only result; physical phone testing remains needed.


## M6 PostgreSQL isolated restore acceptance (draft PR #33)

The existing clean-start GitHub Actions job first confirms the restore
script **refuses missing opt-in**. It then loads synthetic E2E staff,
serialized UNO, menu, table and a known manager audit event into its
disposable database. The script runs transactional `pg_dump -Fc`,
confirms physical inventory TABLE DATA is present, restores into an
absent destination DB and verifies:
- all public table counts, including migration and audit tables;
- public constraint identity/type/validation and non-CHECK definitions, plus index structure and partial-filter literal values;
- deterministic content MD5 comparisons for staff, roles, copies,
  menu, audit events and the Flyway schema history;
- rejection of invalid copy condition and orphaned game foreign key after restore;
- integrity of the linked synthetic audit actor and serialized unit.

The script removes only its own temporary archive and verification DB
under an EXIT trap; normal CI destroys the entire disposable Compose
environment afterward. No production database or external payment
account is involved. This does not establish backup schedules or RPO/RTO.


## M6 security gate (stacked draft PR #34)

The eighth GitHub Actions job runs with read-only repository
permissions and disabled checkout credential persistence.
It first executes `node --test scripts/security-preflight.test.mjs`,
covering positive fake GitHub/AWS/PEM/Stripe token findings,
browser localStorage/HTML sink detection, scope boundaries,
documented synthetic secrets and non-disclosure of secret values.
Then `node scripts/security-preflight.mjs` scans the current tracked
tree using `git ls-files -z` and fails on matches with only
file:line:rule diagnostics. Finally `npm audit --omit=dev
--audit-level=high --no-fund` checks **production frontend
dependencies**, using the committed `apps/web/package-lock.json`
and npm's advisory feed. No real credentials or production DB.
All original 7 jobs continue and must pass at the same HEAD.
The scanner deliberately does not claim full Java/SAST or
historical-secret coverage.


## M6 Java/TypeScript CodeQL and Maven advisory coverage (draft PR #35)

Two additional workflows run on PRs (including stacked branches):
`.github/workflows/codeql.yml` executes CodeQL extended-security
queries for Java/Kotlin under instrumented Java 21 Maven compilation
and JavaScript/TypeScript without a build, uploading the SARIF alerts.
`.github/workflows/osv-maven.yml` executes the documented OSV-Scanner
**PR comparison** on `backend/pom.xml`, detecting newly introduced
Maven dependency advisories against the target branch; **full**
fail-on-vulnerability scanning additionally runs on `main` pushes
and weekly schedule. OSV supports transitive Maven resolution, but
the resolver may omit some test-scoped dependencies.

Review the workflow conclusions **and Code Scanning alerts** before
sign-off. Passing CodeQL means analysis and SARIF upload succeeded,
not that zero findings exist. Existing eight CI jobs stay unchanged.
No production database, keys or payment integrations are accessed.


## M6 runtime Docker sandbox acceptance (draft PR #36)

The clean-start Docker Compose CI job now calls
`bash scripts/verify-container-sandbox.sh api` and
`bash scripts/verify-container-sandbox.sh web`. The two-replica
live catch-up job independently checks both `api` and
`api_replica`. These are **effective container checks**, using
`docker inspect` and an unprivileged `docker compose exec`
runtime probe for no writable `/app` file and valid, volatile
temporary writes. They assert read-only root, `CapDrop=ALL`,
no-new-privileges, `PidsLimit=512`, nonroot image user, bounded
tmpfs `/tmp` and writable Next.js cache mount. Existing backend,
stock, live-event, browser HTTP/HTTPS, and PostgreSQL backup/restore
CI jobs remain unchanged and must pass on the new image profile.
CI is not a memory exhaustion or persistent-cache correctness test.
