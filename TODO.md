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

### M2 customer browser follow-up (stacked PR #5)

Customer UI now includes active-table join proof, visit-scoped HttpOnly session, menu and modifier selection, server-priced versioned cart, cash checkout and status tracking, backend guest logout, and paid-only own receipt reads by order ID. Integration tests cover receipt visibility and session revocation. **Keep the full guest journey and M2 acceptance checkboxes open** until authenticated browser end-to-end, join abuse rate limiting and real-origin cookie/CSRF checks are complete. See [docs/M2-GUEST-ORDERING.md](docs/M2-GUEST-ORDERING.md).

### M2 staff table-management UI (draft PR #6)

Added first role-protected table inventory and browser actions to open visits, rotate short-lived proof, and close visits after paid/served checks. Ordinary waiters only see manageable visit IDs for visits they opened; supervisors can manage all. Proofs are shown only once in the result of a privileged POST, not through GET or QR/NFC links. M2 acceptance remains **OPEN** pending authenticated staff/guest E2E, real browser and network checks, rate-limiting of guest joins, and actual operational trial. See [docs/M2-TABLE-MANAGEMENT.md](docs/M2-TABLE-MANAGEMENT.md).

### M2 guest join security guard (stacked draft PR #7)

Added a PostgreSQL V10 fixed-window HMAC-keyed rate limiter with safe HTTP 429 and 10-minute Retry-After behavior, separate committed throttle transaction, bounded peer/table/pair attempt budgets, stale-counter cleanup and negative integration tests. This hardens the backend but **does not close the public launch security gate**: the Next.js server-to-server join means socket IP can be shared; a trusted edge per-client rate limiter plus network/origin hardening and malformed-body controls are still required before internet exposure. See [docs/M2-GUEST-JOIN-THROTTLE.md](docs/M2-GUEST-JOIN-THROTTLE.md).

### M2 Chromium real browser E2E (stacked draft PR #8)

Added isolated Chromium workflow with ephemeral synthetic PostgreSQL fixture and independent waiter/cashier/kitchen/guest sessions, covering the full cash/receipt/table lifecycle. Role isolation, unauthorized cross-guest read, cookie security attributes, modifier pricing and non-PAID-before-cash also covered. **Do not mark production acceptance complete**: actual proxy/origin/CSRF/TLS, manual mobile/a11y, payment policy and external provider remain open. See [docs/M2-BROWSER-E2E.md](docs/M2-BROWSER-E2E.md).


### M2 origin/role boundary (draft PR #9)

Implemented explicit staff-role requirement for staff identity/logout as defense-in-depth; the existing guest bearer filter already excludes staff paths, plus Java regression assertions. Server Action body size capped at 32KB; new Chromium negative tests attack forged Origin and large payload, and assert unsettled table cannot close. **Remain open:** real HTTPS reverse-proxy forwarded-host/Origin review, trusted edge request limits, native mobile/accessibility and external money/PII approval. See [docs/M2-ORIGIN-ROLE-HARDENING.md](docs/M2-ORIGIN-ROLE-HARDENING.md).

### M2 HTTPS edge staging acceptance (draft PR #10)

Added an isolated nginx:1.27-alpine HTTPS edge, strict localhost Host handling, forwarded-header overwrite, direct-peer POST-only login/join throttling and full browser acceptance CI through a self-signed TLS endpoint. **Public deployment remains blocked** on actual trusted certificate/domain/SNI configuration, authenticated staging topology, real-client limiter behind the real CDN/LB chain, CSP/HSTS and payments/privacy approval. See [docs/M2-HTTPS-EDGE-ACCEPTANCE.md](docs/M2-HTTPS-EDGE-ACCEPTANCE.md).

### M2 live update slice (draft PR #11)

Implemented BFF-authorized, bounded, change-only SSE and server-render refresh across OCC/KDS/waiter/tables/guest orders. Browser tests verify cross-role updates without reload and foreign guest stream denial. Still open: event-driven outbox dispatcher, connection budgeting and load tests, production proxy and accessibility acceptance. See docs/M2-LIVE-UPDATES.md.

### M2 durable live relay (draft PR #12)

Added V11 live signals, transaction-locked multi-instance-safe outbox relay and commit-only PG NOTIFY hints with idempotent replay/rollback tests. **Still needed before real push:** authenticated event cursor/LISTEN consumer, waiter/guest per-object scoping, late-subscriber replay, bounded connection pool and production load testing; plus poison-event quarantine and retention/monitoring policy. SSE from PR #11 remains snapshot-based until those are complete. See docs/M2-DURABLE-LIVE-RELAY.md.

### M2 scoped durable subscriber (draft PR #13)

Added shared PostgreSQL LISTEN wakeup, scoped durable cursor query, bounded 48-request backend long-poll, revocation rechecks and BFF SSE bridge for OCC, Kitchen, Waiter and guest orders/receipts. Only event-change notifications leave Next.js, with no token/PII. Table status watcher still polls. **Production open:** async concurrency/load tests, request executor sizing, proxy failure recovery and metrics, poison-event retries/quarantine, outbox retention, mobile/accessibility, real TLS setup and payment rules. See docs/M2-LIVE-EVENT-SUBSCRIBER.md.

### M2 table lifecycle event subscriber (draft PR #14)

Completed transactional TABLE_VISIT open, rotate and close events, V12 separate durable signal table, shared relay and scoped waiter `tables` cursor. A second staff browser tab validates live occupancy updates without reload. **Still open:** async subscriber capacity/soak tests, admin inventory events, offline and multi-replica recovery, poison-event monitoring, public HTTPS and live financial/consent policies. See docs/M2-TABLE-LIVE-EVENTS.md.

### M2 live capacity and safe observability (draft PR #15)

Added per-guest (2) and per-staff (6) long-poll identity limits within global cap (48), idempotent permit release, configurable bounded validation, aggregate Micrometer metrics for capacity/denials/listener/backlog, read-only PostgreSQL backlog sampling and virtual-thread race tests. Still open: servlet async executor, multi-instance distributed admission, load/soak benchmarks, measured p95 latency and DB connection headroom, metrics exporter/alerts, poison event quarantine and production approval. See docs/M2-LIVE-CAPACITY.md.

### M2 async cursor and measured admission gate (draft PR #16)

Moved live-cursor waits with an `after` parameter to Spring MVC asynchronous Callable + JDK virtual threads, retaining the global and per-principal resource budgets from PR #15. Added integration async-dispatch checks and an independent synthetic multi-identity load CI measuring concurrent accepted/rejected waits, health responsiveness, elapsed duration and p50/p95. **Still open:** measured distributed/multi-replica soak and network-disconnect behavior, DB pool sizing, secured telemetry dashboards/alerts, p95 customer notification SLA, production TLS and ingress gates. See docs/M2-ASYNC-LIVE-LOAD.md.

### M2 two-replica live event recovery (draft PR #17)

Added real two-container / shared-PostgreSQL integration acceptance: simultaneous table long-polls receive one committed open event; replica B misses a join-proof rotation while stopped, then reads the durable cursor after restart, receives a subsequent close event and retains role gates. This establishes **one bounded recovery scenario**, not production multi-node availability. Still needed: failed-over DB primary, actual network interruption and abort semantics, real CDN/proxy and distributed rate-limit decisions, multi-hour load/soak, alert delivery and approved data-retention policy. See docs/M2-MULTIREPLICA-RECOVERY.md.

### M2 cancel/reconnect resilience (draft PR #18)

Added explicit cancellable DeferredResult live cursor workers, idempotent interrupt, no abandoned fake success, tested release of per-user/global slots on cancellation; browser offline/visibility SSE close, capped reconnect backoff, latest-state re-sync on return. Chromium tests miss a cashier approval while kitchen is offline then recover without a reload. **Remaining:** actual half-open client/proxy disconnect test, production CDN/LB, cancellation timings under load, multi-hour soak, distributed admission, secured metrics and alerts. See docs/M2-DISCONNECT-RECOVERY.md.

### M4 physical game loans initial vertical slice (stacked draft PR #19)

Added V13 serialized game inventory, request/assign/handover/return/cancel status machine, active-copy and active-visit uniqueness, responsible-waiter restrictions, manager/admin catalog registration, guest/staff pages, guest session and close-visit protection, synthetic concurrency/browsers tests. **Still open for full M4:** damaged/lost/maintenance/incident policy; guest return-pending; event-driven live task notifications; production UI/accessibility and physical staff acceptance; complete catalog/admin edit/inactive management; fees/deposits only after owner approval. See docs/M4-PHYSICAL-GAME-LOANS.md and ADR-0007. Do not mark the original M4 checklist fully completed.

### M4 game loan live event follow-up (stacked draft PR #20)

V14 adds durable GAME_LOAN live signals and same-transaction outbox publication
for request/assign/handover/return/cancel. Guest live cursors are scoped to
**the owning guest and visit**; waiter cursors only to visits opened by that
waiter, with supervisor override. Guest/staff game pages refresh authorized
server data from change-only SSE, including offline snapshot recovery.
CI verification pending. Catalog-only inventory edits still require manual
refresh and M4 damage/loss/maintenance, guest return request and staff physical
acceptance are not done. See docs/M4-PHYSICAL-GAME-LOANS.md.

### M4 guest return-pending slice (stacked draft PR #21)

Added V15 return request state with an owner-scoped guest API and UI, durable
game update event, waiter inspection workflow, allocation exclusivity, table
close guard and PostgreSQL/Chromium tests. An accepted pickup request is
**not** a return: the physical copy remains assigned until staff verifies
its serial. CI acceptance pending. Damage/loss, maintenance, guest disputes
and policy-dependent fees remain unimplemented.

### M4 quarantine & inspected restock (stacked draft PR #22)

V16 history table records every condition change with staff ID, copy ID,
optional returned loan and required reason. A verified damaged return
atomically marks loan RETURNED and copy DAMAGED; stock stays unavailable
until manager/admin inspection restores AVAILABLE. Manager may also
quarantine an idle copy, but no role can change an assigned unit.
Unauthorized, invalid and replayed actions are rejected; Java/PostgreSQL
and HTTP/HTTPS browser acceptance gate this slice. No damage charge,
refund, guest liability, lost-property or disposal resolution has been
approved or implemented. Inventory-only updates are manually refreshed.

### M4 copy-condition history screen (stacked draft PR #23)

Manager/admin inventory dashboard now exposes the 100 latest immutable
inspection/quarantine/restoration history entries through a separate
role-gated no-store API; waiter/cashier/kitchen cannot read the notes.
Historical reasons cannot be altered from the UI; no guest or financial
data is included. Next still needed: paginated operational history,
owner-approved LOST/RETIRED incident policy, inventory realtime updates,
staff field trials, security review before a public pilot.

### M4 automatic catalogue/stock refresh (stacked draft PR #24)

V17 adds game-inventory change markers to existing scoped `games` SSE
without another connection. Catalogue creation, unit addition,
allocation, inspected return, and manager quarantine/restoration now
publish safe same-transaction events for all authenticated viewers.
Private loan events stay identity-scoped; copy serial, audit notes and
customer/financial data never enter live messages. PostgreSQL and
HTTP/HTTPS browser tests cover cross-table stock updates. Production
release still needs physical-device testing, operational inventory
paging/notifications, additional damaged/lost policy and security review.

### M4 indexed copy search and paging (stacked draft PR #25)

Manager/admin game inventory now has backend-powered, case-insensitive
game-name/serial search, condition filters, 25-item pages and
previous/next navigation, rather than the initial unpaged 250-copy
view. V18 adds a copy-condition/code index, keeps legacy API
compatibility and adds PostgreSQL/Testcontainers and Chromium tests.
Server validates all filters and caps expensive pagination; no new
guest serial exposure, fee, loss liability or automatic maintenance
resolution. Large-scale cursor pagination and history paging may be
needed after real operational data is available.

### M4 condition audit search + pagination (stacked draft PR #26)

Manager/admin can now search all physical-copy condition history by
unit, game, staff or reason and filter outcome DAMAGED/AVAILABLE in
25-entry pages, instead of relying on only the latest 100 entries.
V19 includes sort/filter indexes, the legacy API stays compatible and
read-only audit records cannot be rewritten. History and unit filters
have separate page states in the staff UI. PostgreSQL and HTTP/HTTPS
browser tests cover old records, access denial and query validation.
Still open: operational policy for truly LOST/RETIRED copies, incident
ownership, high-volume keyset pagination, manager field trials and
production security gates.

### M4 missing game incident report and manager verification (draft PR #27)

Added staff missing-copy report with reviewable OPEN incident, manager-only
dismiss/confirm actions, exact-unit verification, durable incident/audit
records and scoped change-only signals. OPEN reports do not free
stock, permit table closure, or silently imply theft. Confirmed LOST
atomically retires the active loan and removes the copy from inventory
without fees or guest liability. Dismissal resumes normal physical
handover/return workflow. PostgreSQL security/transition and browser
acceptance added. Still requires approved LOST copy recovery/claims
policy, manager incident paging, staff/mobile acceptance, retention
rules and operational incident review before a public pilot.


### M4 recovered LOST copies, verified quarantine and restock (draft PR #28)

A manager can now record a found LOST serialized copy with an exact
physical code and discovery reason; server requires a prior confirmed
LOST incident and quarantines as DAMAGED without changing the old loan
or incident. A second inspected restoration is needed before stock
becomes AVAILABLE. Both steps have permanent per-copy condition
history/audit and scoped inventory live marker. PostgreSQL and
HTTP/HTTPS browser checks cover full workflow. Still open: formal
claims/retention policy, paging for loss incidents, physical field
testing, manager incident notification and commercial rollout gates.


### M4 manager incident search/paging + OPEN backlog (stacked draft PR #29)

Added full-DB manager-only incident search by game, serial, table,
reporter and notes, exact state filters, OPEN-first pagination
(25/page; max page 2000) and independent outstanding-review count on
the staff dashboard. Flyway V22 OPEN-first sort index, strict backend
validation, and PostgreSQL/Chromium regression coverage added.
Legacy 100-entry API unchanged. Future work: keyset paging at scale,
manager role-specific operational alert entry points, field usability,
record retention/appeal rules and security/production review.


### M4 manager dashboard incident alert (stacked draft PR #30)

Main staff home now shows manager/admin-only OPEN incident totals,
three newest minimal previews and a direct review-queue link.
The existing game SSE revalidates server-rendered status in real time
and after tab visibility resumes, without new polling or push
infrastructure. An unavailable incident summary shows a warning.
PostgreSQL role/data-minimization tests and Chromium HTTP/HTTPS
0→1→0 manager notification lifecycle added. Remaining pilot work:
operational review by staff, accessibility/mobile testing, incident
record retention/appeals and authorized production rollout.


### M4 manager-only audit evidence for physical game incidents (draft PR #31)

A read-only per-incident manager screen now presents reporter and
reviewer staff identities and timestamps, original notes, event audit
operations, and request correlation IDs from existing transactional
`audit_logs`. Both backend and UI enforce manager/admin access,
with 404 for missing incidents and fail-closed unavailable state.
PostgreSQL/Chromium integration validates report → explicit review
audit attribution and no guest/payment data leakage. No migration or
production configuration changes. Remaining: incident evidence
retention and appeals policy, explicit incident staff shift handoff,
mobile field usability/accessibility checks, and release security
review before a real-world cafe pilot.


### M4 phone/tablet staff accessibility pilot (draft PR #32)

Improved staff-only CSS for 320–768px phone/tablet layouts, safe
long-serial/note/audit-ID wrapping, keyboard focus visibility,
44px+ touch targets, mobile-width actions and separated risky
decision forms. Manager OPEN notification, incident search, copy
inventory and audit history now use anchored navigation to reduce
long scrolling after filter/reset/next-page actions. Synthetic
Chromium mobile tests verify responsive overflow, interactive
target size, keyboard focus and evidence timeline semantics on
HTTP and HTTPS. Still required: hands-on touch/zoom/assistive
technology testing on real Android/iOS before a cafe field pilot.


### M6 isolated PostgreSQL backup and restore proof (draft PR #33)

The clean-start Docker CI job now exercises a **guarded** `pg_dump -Fc`
and `pg_restore --exit-on-error` on a second, newly created DB with
synthetic staff/game/audit data. Tests compare table counts, schema
constraint identity/type/validation, index structure/partial filter values,
restored CHECK/FK rejection, six representative content fingerprints,
Flyway migrations and the audit actor-to-unit relationship. Unsafe
invocation or existing destination is refused; the source is never
overwritten. This is one CI rehearsal, **not** the completion of
production backups, RPO/RTO, PITR, encrypted offsite retention,
multi-hour soak, or payment reconciliation release gates.


### M6 security baseline in CI (stacked draft PR #34)

Added a separate fail-closed tracked-tree secret scanner with six
self-tests and safe file/line-only findings. First-party browser
source cannot add local/session token storage, raw HTML injection
or dynamic eval without an explicit security review and removal.
`npm audit --omit=dev --audit-level=high` now gates known
high/critical production frontend advisories. Original seven
integration/browser jobs remain; full Java dependency/SAST,
historical secret scanning, runtime image scanning, real security
incident drill and branch protection remain release blockers.


### M6 CodeQL and Java/Maven vulnerability scanning (stacked draft PR #35)

Added CodeQL `java-kotlin` and `javascript-typescript` extended
security scans with Java 21 Maven instrumentation, and OSV Maven
`pom.xml` advisory scanning for **new PR vulnerabilities** plus
full weekly/main scans. Scanners upload Code Scanning results and
do not receive deployment credentials. Passing CodeQL job is not
proof of zero alerts; an OSV PR pass cannot clear inherited
vulnerabilities. Still required: triage all findings, security
release policy, dependency update SLAs, container scanning,
privileged auth review, real pilot testing and branch protection.


### M6 container runtime least privilege (stacked draft PR #36)

Restricted API, web and second API replica Compose services to
non-root users, read-only rootfs, `cap_drop: ALL`, disabled
privilege escalation, bounded pids and volatile tmpfs for
`/tmp` and Next.js runtime cache. Added effective Docker
inspection + active write-denial probes in clean-start and
two-replica CI, while leaving PostgreSQL and localhost Nginx
startup untouched. Still open for production: image CVE scanning,
deployment topology hardening, DB/edge least privilege, egress
control, cache policy, secrets delivery, memory/CPU stress and
real restore/incident drills.


### M6 container image vulnerability review (stacked draft PR #37)

Added a separate Trivy image scan workflow for actual API and web
runtime images, with OS + library HIGH/CRITICAL SARIF output,
five tests of the scanner result policy and CI failure for
fixable high/critical advisories. Weekly/main scanning is included
to discover new vendor advisories. Unfixed alerts stay visible
for mandatory manual triage; registry provenance, signatures,
digest pinning, PostgreSQL/Nginx image scans and production
security approval remain open.

### M5 in-session receipt print increment (draft PR #38)

A guest-scoped PAID receipt HTML detail and browser print/Save as PDF action are added on top of the cash vertical slice, with negative Chromium checks for unpaid/foreign orders. This is a **partial** M5 improvement, not final download-link, phone-delivery, financial-reporting or public production readiness. Do not mark M5 complete before authenticated CI results and the remaining requirements pass.


### M5: first daily cash report (draft PR #39)

A WIB daily cash gross report and top-ten item summary are implemented on a stacked development branch with a role-protected /staff/reports screen for cashiers/managers/admins. The source is finalized immutable CASH receipts, not unpaid orders. Date validation, no-store, access denial and synthetic PostgreSQL/Chromium coverage are added. This is **partial** daily operations reporting: no bank/drawer reconciliation, QRIS/VA, refunds, reporting exports, receipt messaging or production pilot. Await exact-head CI; keep the original M5 checkbox open until complete.


### M5 staff account lifecycle (draft PR #40)

Manager/admin read-only staff directory and safely audited enable/disable of ordinary staff (WAITER, CASHIER, KITCHEN, or no role) are implemented on a stacked branch. Disabling instantly invalidates existing sessions and revokes their tokens. Manager/admin targets and self-disablement are forbidden by the backend to avoid lockout; password reset, user creation, role assignment, privileged-account recovery and MFA remain unimplemented. Synthetic PostgreSQL/Chromium authorization and replay-conflict tests are included; not production-ready until exact-head CI passes.

### M5: admin-only ordinary staff provisioning (draft PR #41)

The ADMIN role can provision a **disabled** WAITER/KITCHEN/CASHIER with normalized login, BCrypt password and audit reason, without granting manager/admin roles. Enabling remains a separate audited step. Synthetic PostgreSQL tests cover 401/403, rejected escalated roles, weak password, duplicate/login case, disabled authentication, first activation and audit integrity; Chromium acceptance covers manager exclusion and admin creation/activation. This is a **partial** M5 increment, not a complete staff lifecycle: password reset/rotation, invitation delivery, operator recovery, MFA and authorization policy remain open. Await PR-head CI; no main merge, external secrets or production activity.

### M5: staff self-service password rotation (stacked draft PR #42)

Authenticated WAITER/KITCHEN/CASHIER/MANAGER/ADMIN can replace their own
password after verifying the current password. The new password follows the
16–72 printable-ASCII BCrypt-safe policy. V23 persists a five-failure/15-minute
per-account throttle; successful rotation atomically updates the password,
revokes **all** staff sessions and records a secret-free audit event. Login
serializes on the staff row to prevent an in-flight old-password login from
creating a surviving token. The protected /staff/account browser form clears
the HttpOnly cookie on success and requires a fresh login. PostgreSQL negative,
revocation, throttle and race tests plus isolated Chromium browser acceptance
are proposed; verify exact-head CI before marking this increment accepted.
**Still open:** enforced first-login rotation, password reset/recovery, secure
credential distribution, MFA, operational account policy and production review.

### M3 payment adapter and attempt persistence foundation (draft PR #44)

Introduced typed PaymentGateway (QRIS/VA) and deterministic **non-payable** process-local fake adapter plus V24 digital payment attempt metadata with PostgreSQL foreign keys, scoped idempotency, one-pending-attempt constraint, matching method/amount and immutable financial identity. Java unit tests exercise invalid payloads, concurrency, idempotency, fake expiry and non-payable QR/VA; Testcontainers tests validate digital attempt constraints against synthetic QRIS and CASH intents. No gateway endpoint, frontend payment method, webhook, provider network, receipt, or payment PAID transition was activated. This does **not** close M3 and is not a production payment integration.
