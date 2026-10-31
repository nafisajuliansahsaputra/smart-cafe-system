# Security and Privacy — Smart Cafe System

**Status:** Threat model and implementation gates. No deployed security controls are claimed yet.

## 1. Assets and trust boundaries

Sensitive assets: staff credentials; table visit join proof; guest session; guest phone numbers; order snapshots; payment attempts, provider server key and webhook; game inventory; audit; receipt access tokens.

Boundaries: public browser -> guest API; staff browser -> privileged API; payment provider -> webhook; job worker -> external receipt provider; backend -> DB. Frontend UI is never trusted to establish an identity, role, amount or paid state.

## 2. Primary threats and controls

| Threat | Required control and test |
| --- | --- |
| Attacker photographs static table QR | QR is only table locator; visit-specific short-lived join proof/guest session required |
| Guest submits for another table/guest | Server scopes to authenticated active visit; deny with 403 or safe 404 |
| Repeat checkout/click/replay | Unique idempotency key + request hash + DB constraints |
| Forged payment success | Verify provider signature, order ID, amount, status, merchant/environment; reconcile with status API |
| Duplicate/out-of-order provider notifications | Event dedupe, monotonic aggregate logic, paid cannot regress |
| Cashier marks false PAID | Role-scoped tender record, server money checks, audit/review and privileged correction |
| Stolen receipt link | Short-lived random bearer, hash-at-rest, rate limit, no referrer/log leakage |
| Stolen phone data | Encryption at rest, masked UI, purpose-limited access, retention policy |
| Game stock race | DB locking/unique active allocation constraint |
| Refund abuse | Manager/explicit permission, reason, limits, immutable event/audit record |
| Staff token theft/CSRF | Secure cookies, session rotation/revocation, CSRF/origin validation |
| SQL injection/XSS | Parameterized queries, validation and safe UI rendering/CSP |
| Abuse of login and guest join | Rate limit, throttling and monitored anomalies |
| Receipt webhook/message SSRF | No arbitrary caller-provided URL for provider fetch; strict adapter domains and timeouts |
| Admin cross-cafe access in future | Tenant scoping before implementing SaaS; not assumed now |

## 3. AuthN/AuthZ

- Staff uses secure authentication, least privilege and ability to disable sessions; MFA encouraged for manager/admin before live production.
- Guest uses per-visit server-minted short-lived credential, not number or table QR alone.
- No staff roles derived solely from client-submitted role fields.
- Domain-level authorization for object ownership and staff capabilities.
- Kitchen receives only fields needed to prepare orders, not phone/financial secrets.
- Managers approve refunds/voids and exceptional closure. Cashier gets only approved financial permissions.
- Defense-in-depth in DB using constraints and scoped access. Never expose privileged DB credentials to browsers.

## 4. Payments

- Choose only documented API integration using sandbox during development.
- Separate server key from client keys and whitelist secrets by environment.
- Treat UI redirect/QR screenshot as untrusted; wait for verified callback or authoritative status query.
- Compare exact merchant order reference, amount, currency, expected status, merchant and environment before marking payment paid.
- Event fingerprint + transaction guards prevent duplication.
- Reconcile delayed, missing and contradictory callbacks; never silently disregard paid money.
- No PAN/cardholder sensitive payment data stored in app DB.
- Do not hold DB transaction across external HTTP calls.

## 5. Phone, consent and retention

- Optional collection. Explain receipt purpose and selected messaging channel.
- Record opt-in, channel, time, notice version, revocation; keep marketing opt-in entirely separate if ever introduced.
- Encryption at rest for sensitive phone/destination; role-based viewing with masking.
- Define retention, accountabilities, subject-rights workflow and backups lifecycle before processing real customer data.
- No real customer data in development or public demo.
- Comply with applicable Indonesian personal-data rules; legal review required before public commercial use.

## 6. Operational security

- TLS/HSTS as suitable, secure session cookies, restricted CORS, CSRF for cookie auth, content security policy.
- Secrets via environment/manager and CI secrets; .env example contains placeholders only.
- Structured logs with request ID, order ID, audit ID; redaction of phone, tokens, credentials, raw webhook secret/signature.
- Dependency/SAST/secret scanning and migration checks in CI.
- Regular backups; tested restore. No direct prod DB edits without audited incident process.
- Central rate limits around high-risk public endpoints; security alerts for repeated failed joins/failed webhook validation.

## 7. Release-blocking tests

- Unauthenticated staff API calls denied.
- Cashier cannot perform manager-only refunds; kitchen cannot mark payment paid.
- Guest cannot view another guest's private order or receipt.
- Expired visit credential cannot checkout even if table alias remains.
- Invalid/fake webhook cannot mark paid; duplicate valid event is no-op.
- Two parallel checkouts cannot oversell last limited stock or last game unit.
- Phone and secrets never appear in API logs, public error responses or leaked data projections.
- Deactivated staff loses permission promptly.
- CSRF/XSS/SQL injection controls evaluated with tests and review.

## 8. Security disclosure / incident

A suspicious payment or data incident requires containment, payment reconciliation, credential rotation if needed, evidence preservation, customer/authority notification assessment under applicable law, root-cause analysis and regression coverage. Never repair by deleting audit or historic payments.

## 9. M2 implemented authentication boundary (draft)

This development vertical slice uses a stateless **Authorization: Bearer** header (opaque 256-bit random token, SHA-256 hash only in the DB), not browser cookies. Spring Security disables CSRF specifically because credentials are not automatically attached by browsers; **do not switch to cookie sessions without adding CSRF/origin defenses**. The staff browser login/store design is not implemented and must not store bearer tokens in localStorage. All financial/order routes reject unauthenticated roles at the backend.

- Staff sessions are 8 hours, revocable and immediately rejected if the staff account is disabled. Roles are read from PostgreSQL per authorized request.
- Invalid staff credentials use a generic response and persistent 5-failure / 15-minute login throttling per normalized login; distributed IP-level rate limiting remains a release blocker.
- Rotating staff-issued join proof is valid for 10 minutes. Static QR table aliases never grant guest authorization.
- Guest sessions are 8 hours, scoped to one visit and invalid when closed or explicitly revoked; no phone number is required or stored by implemented join.
- Local initial admin bootstrap requires an explicit `dev-bootstrap` profile and secret environment values, refused if any staff account exists. It is not a production provisioning mechanism.
- DB constraints and audited transactional writes back financial and fulfillment invariants. Integration tests exercise role denials, wrong guest receipt access, old join proof, disabled staff and duplicate checkout.
- Missing production controls: full role/guest object-level security review, staff BFF/cookie+CSRF UI, join/IP throttling, SAST/dependency scans, encrypted PII processing, staff credential reset/management and operational gateway reconciliation.

See [ADR 0003](decisions/0003-api-bearer-auth.md) and [local bootstrap guide](M2-LOCAL-BOOTSTRAP.md).

## 10. M2 incremental enforcement

- PostgreSQL V5 prevents a receipt from being inserted unless the correlated payment intent is fully PAID with the matching total.
- Until actual task assignment exists, only the waiter who opened a visit may close/rotate it, mark that visit's order served or record its cash; supervisory roles have separate privileges. This is stricter than role-only access.
- OCC filters are parameterized and status values validated; query errors use the public safe error envelope.

## 11. Cart/inventory authorization gates

Guest cart updates and order checkout take identity exclusively from the current scoped token, never guest IDs in client payload. Limited quantities are enforced by PostgreSQL atomic updates with `limited_quantity>=requested`, not frontend state. Manager/admin stock changes are version-checked, reason-required and audited. Cashier-only pre-kitchen rejection releases stock in the same locked order transaction, with allocation evidence preventing accidental double credit. Inventory search uses parameterized JDBC statements. Further auth review, inventory adjustment reconciliation and full E2E are still required before pilot.

## 12. Catalog variants financial boundary

Only manager/admin roles can write item prices, visibility and variant group/option configuration. New items start HIDDEN. Item edits and option edits require the current revision, with stale writes rejected. Customer `variantOptionId` is treated solely as an untrusted selector: checkout verifies active group ownership, required selection, active option and adjusted unit price on the backend while holding a shared lock against concurrent catalog edits. Stock is still enforced on the parent menu item, not separately per option. Price/variant snapshots are part of the persisted order/receipt history; changing current catalog options never rewrites old financial evidence.

## 13. Multi-group modifier constraints

Each untrusted `variantOptionIds` array is bounded (at most eight), duplicate-checked and matched to active groups belonging to the requested menu item. All required groups must be represented exactly once. The backend reads catalog option prices under shared database row locks while checkout holds the parent menu item lock. Catalog writers take a parent-item write lock before creating/editing groups or options, serializing catalog changes with order-time snapshots. The canonical direct checkout idempotency fingerprint sorts UUID selections, preventing alternate selection ordering from causing duplicate orders. Manager/admin permissions remain mandatory for modifier writes. Legacy one-group lookup fails visibly when more than one group exists.

## 12. M2 guest browser security additions (draft PR #5)

- Static table QR/NFC alias grants no ordering permission; a guest must enter a separate waiter-issued join proof (valid 10 minutes) into a POST form, never a URL or analytics field.
- Guest token is retained in a `/visit` HttpOnly Strict SameSite cookie (Secure in production) and forwarded by the Next.js server to fixed API paths. Browser JS and localStorage do not receive the bearer.
- `POST /api/v1/me/logout` revokes server token and clears the cookie; when backend revocation cannot be confirmed the UI warns rather than claiming success.
- `GET /api/v1/me/orders/{id}/receipt` binds receipt to owner and PAID state, returns 404 for foreign/unpaid order and no-store for successful response.
- Backend remains authoritative for cart version conflicts, stock, modifier group validation, prices and checkout idempotency. Client total is explicitly an estimate.
- **Release blocking:** per-IP/device guest join throttling and browser E2E, CSRF/origin/proxy verification, secure-cookie QA and real-cafe operational policy. Do not expose this demo to actual public users or accept real customer data/payments yet.

## 13. M2 guest join abuse throttle (draft PR #7)

New table `guest_join_throttles` stores only HMAC-SHA256 bucket fingerprints, 10-minute fixed-window counts and timestamps. A separate transaction consumes budgets **before** guest proof authentication, so incorrect proofs cannot roll back attempt counters. Backend limits: 1,200 per backend socket peer per 10 minutes, 60 per table alias and 24 per peer+table. A 429 `RATE_LIMITED` response includes `Retry-After: 600`, `Cache-Control: no-store`, and no token/proof. Counters older than two days are periodically purged. No actual IP address or join secret is saved to this table.

The HMAC secret is a mandatory 32-byte-or-longer environment variable `SCS_JOIN_THROTTLE_SECRET`. API refuses startup without it. Change development placeholders before use, never commit real secrets, and use the same key across backend replicas until a planned rotation.

**Important limitation:** backend reads `HttpServletRequest.getRemoteAddr()` and rejects potentially forged Forwarded/X-Forwarded-For identity claims. With guest Server Actions hosted on Next.js, many real guests may share one backend socket peer. The per-table guard works, but this is not verified individual client IP limiting. A correctly configured trusted CDN/reverse-proxy rate limiter, documented trusted peer rules and request-size/malformed-body abuse controls remain **mandatory launch blockers**. Do not treat this PR as completed public DoS protection.


## 14. Staff principal isolation and Server Actions origin checks (draft PR #9)

- Previously `/api/v1/staff/auth/me` and `/api/v1/staff/auth/logout` used a generic `authenticated()` guard. **The existing GuestBearerFilter already excludes staff-auth routes**, so a guest bearer received 401. Explicit staff role checks (WAITER, CASHIER, KITCHEN, MANAGER, ADMIN) add defense-in-depth against future filter changes; a guest bearer continues to receive 401, and its session remains active.
- Next.js Server Actions remain POST-only with default Origin-versus-Host / X-Forwarded-Host CSRF validation. **No `allowedOrigins` exceptions**, wildcards or public domain bypass are configured. Actions are limited to 32KB form bodies; this app currently uses small text forms without uploads.
- HTML pages under `/staff`, `/visit` and `/t` carry no-store/strict referrer where applicable; no-sniff, deny-frame and disabled camera/microphone/geolocation response headers have been added. Do not claim this constitutes a complete CSP.
- Real Chromium negative tests submit an authenticated staff Server Action with an untrusted Origin and verify rejection without revoking the valid staff session, attempt oversized 64KB login action and check rejection without cookie issue, and verify waiter cannot close a table with an unsettled order.
- **Release blockers unchanged:** the public HTTPS reverse proxy must strip caller-controlled Forwarded and X-Forwarded-Host; configure trusted origin/host routing, test login/logout and other actions behind that exact proxy, enforce real-client ingress throttles, and verify TLS/HSTS, CSRF under the deployed topology and mobile browser behavior. Never infer proxy safety from localhost CI alone.

## 15. TLS edge and forwarded-header boundary (draft PR #10)

Local-only Nginx TLS overlay: 127.0.0.1:8443, server_name localhost and 421 on unknown Hosts. Only Next.js web is proxied; backend DB and privileged API are not separately exposed through the edge. Caller-supplied Forwarded/X-Forwarded-For/X-Forwarded-Host are removed/overwritten; fixed direct-peer POST ingress rate limits apply to /t/* and /staff/login. The edge's 128KB body limit complements the Next Server Action 32KB cap. Real Chromium acceptance under HTTPS must check login with forged forwarded headers, Secure+HttpOnly+Strict staff cookies and the entire cash visit lifecycle; curl must confirm unknown Host and burst rate denial.

**Hard warning:** these rules are only correct with Nginx directly receiving the caller's TCP connection. A CDN/load balancer in front would shift the peer identity and require a separately audited trusted-real-IP design, not trust arbitrary headers. Self-signed localhost certs, absent public DNS/HSTS/CSP, and disabled internet ingress mean **this cannot be used as a production or internet-facing config**.

## 16. Cookie-scoped SSE (draft PR #11)

Same-origin BFF `/staff/live` and `/visit/live` accept only allowlisted topics and valid order UUIDs. HttpOnly Strict cookies stay out of browser scripts and URLs. The backend rechecks role/session/order ownership on each five-second snapshot; 401/403/404 stop streaming. Events contain only empty change/ready/heartbeat signals, no PII or bearer. Responses are private no-store and unbuffered. Streams last at most 55 seconds. The per-client DB polling load and missing connection admission controls remain launch blockers; event-driven outbox delivery is future work.

## 17. Live relay isolation and durability (draft PR #12)

The new relay only handles ORDER outbox entries, stores minimal UUID/order event type in a dedicated live table, and emits PostgreSQL NOTIFY on commit. It is not exposed to anonymous HTTP routes, and does not put guest credentials/PII/payment bodies into event payloads. A global transaction advisory lock protects publication sequence allocation across replicas and a unique outbox ID prevents duplicate durable signals. Publishing does **not** clear the general business outbox processed marker. A failing/invalid event must not be marked sent. Signed-in guest and waiter listeners are **not yet connected** to this store; their current SSE still uses backend role/ownership-verified snapshots. Backlog monitoring, poison-event handling, multi-client consumption controls and live-table retention are required before real-money production.

## 18. PostgreSQL event cursor scoping (draft PR #13)

Backend endpoints `/api/v1/me/live-cursor`, `/api/v1/ops/live-cursor`, `/api/v1/kitchen/live-cursor`, `/api/v1/waiter/live-cursor` require the existing explicit Bearer token and security role matcher. Durable cursor SQL is filtered by guest ID+visit ID, by waiter-opened visit ownership, or by matching staff role; order-detail subscriptions require UUID and guest ownership. During long-polls token revocation and staff role changes are rechecked. A bounded pool of 48 long polls guards HTTP worker exhaustion in this development implementation. BFF endpoints retain same-origin HttpOnly cookie security, map fixed topics to fixed upstream routes and deliver only empty SSE events. PG NOTIFY is a hint, not an authorization or replay source; the durable signal table remains authoritative. Table-visit watcher still polls. Production needs stricter admission across API replicas, async request sizing, load testing and metrics.

## 19. Scoped table visit signals (draft PR #14)

V12 `live_table_signals` stores table/visit UUIDs, restricted event type and sequence only. Opening, rotation and closing occur in a transaction with a safe outbox event. The hashed join proof and new plaintext proof are never sent via the relay, NOTIFY or browser SSE. All waiter roles can subscribe to occupancy changes already present in the table list, while proof rotation only advances the owning waiter or supervisor's scoped cursor. Kitchen/anonymous requests cannot subscribe. The original business outbox processing marker is not consumed. No claim of production-scale connections or public deployment.

## 20. Per-principal live poll admission (draft PR #15)

The backend long-poll gate now enforces a bounded global concurrent-request limit **and** distinct caps per authenticated guest and staff UUID, with atomic concurrent updates and scoped idempotent permit cleanup. An abusive credential cannot monopolize all 48 default slots. Exhaustion fails closed at HTTP 429, without changing money, table or guest access truth. Registered metrics are low-cardinality and contain no credential/user/visit/order data. Outbox-backlog query errors produce -1/unknown metrics, not stale zero. Public metrics HTTP endpoints remain disabled; actual ingress/DDoS and distributed admission controls remain production requirements.

## 21. Async live cursor admission (draft PR #16)

Only authenticated `?after=` cursor waits are offloaded from the Servlet worker to a JDK virtual-thread MVC executor. Normal cursor reads remain synchronous and subject to existing Spring Security. Async handlers capture the original Bearer header and request ID on the request thread; long waits periodically reauthenticate and recheck scoped order/role access, retaining per-guest/per-staff/global permits with deterministic close on exit. The external SSE endpoint never receives bearer/cursor data. CI load targets **only 127.0.0.1** and refuses other hosts; synthetic credentials/identities are not real. Virtual threads are not an excuse to loosen resource caps or expose metrics; production CDN/proxy, distributed ingress limits and cancellation/load testing remain necessary.

## 22. Cross-replica event isolation (draft PR #17)

The second API in the CI-only Compose overlay has the same strict Spring Security bearer filters and shared scoped durable PostgreSQL cursors as the first, not a public websocket or unguarded broadcast endpoint. A missing NOTIFY while a process is stopped is resolved by a fresh scoped cursor after restart; no guest proof or event payload is sent over NOTIFY or browser SSE. The cross-process test additionally checks anonymous 401/403 and kitchen 403 on the waiter table cursor. The overlay binds only loopback host ports, uses a disposable DB and synthetic credentials, and is not approved for internet-facing service. No global cross-replica admission budget, real CDN IP trust or multi-AZ disaster-recovery claims are made.

## 23. Long-poll cancellation and offline reconnection (draft PR #18)

Authenticated Spring MVC after-cursor endpoints now return a DeferredResult with a virtual-thread task that is interrupted once on Servlet async completion, error or timeout. Existing Bearer filters still authenticate initial and async dispatch requests, and LiveCursorService still rechecks ownership/roles and releases admission permits on every exit. The browser never stores an access token or cursor, never guesses paid state during outages, and re-reads server-authoritative state after EventSource reconnect. Backoff reduces repeated failure requests; reconnect stops when an auth-expired event is received. Proxies may not report idle client disconnects promptly, so the existing 14-second deadline and per-identity/global caps remain mandatory. No public endpoint or unauthorized CORS is introduced.

## 24. Physical game copy assignment and guest isolation (draft PR #19)

Guest Bearer sessions can request a game only in their current ACTIVE visit and list **only their own** loans. A visitor cannot read another guest's loan, assign physical copies, or close tables. Waiter queries and mutations are limited to visits opened by that staff account; supervisors can manage all; kitchen is forbidden. Manager/admin alone can insert game types/serialized copies, all staff changes audited. One copy may have at most one ASSIGNED or IN_USE loan (partial unique constraint plus SELECT FOR UPDATE SKIP LOCKED). Guest request and table closure serialize on a visit row lock. A missing/mismatched physical copy code prevents handover/return. Pending and unreturned loans block visit closure, independent of payment state. No credential, join proof, phone or payment data is emitted in loan responses or newly added events. There is no fee/deposit or lost/damaged charge. Disabled/closed visit credentials cannot submit new requests. See ADR-0007.


## 25. Physical game live notifications (draft PR #20)

V14 signals are keyed to a relational GAME_LOAN id and event name only; the
outbox safe payload remains empty. Signed-in guests can see only their
own guest+visit cursor; an unrelated guest at the *same table* does not
observe an owned loan. Waiters can see only visits opened by their staff
account, while authorized supervisors see a full event cursor. Kitchen and
anonymous watchers cannot subscribe. The BFF keeps session Bearer credentials
server-side and emits neither raw cursor nor loan/copy metadata in SSE.
Subscriptions remain subject to reauthentication, per-principal/global
admission limits, bounded waits and reconnect backoff. A cursor is only a
signal to fetch authoritative state; it cannot approve a handover or return.
Poison-event policy, retention, device acceptance and production ingress
security remain unapproved release gates.

## M4 quarantine and manager remediation (draft PR #22)

A missing or damaged physical game copy cannot be automatically freed by
marking its loan returned. Verified waiter damage returns atomically set
DAMAGED, write a reason-bound condition history, and complete the physically
inspected return. AVAILABLE/DAMAGED manager-only transitions use a locked copy
row and reject any active loan. Copy history includes no customer PII and
never creates a payment, debt or legal finding. LOST/RETIRED states remain
read-only until an explicit policy/reconciliation process exists. E2E uses
ephemeral synthetic-only manager credentials. This still requires final
review of real-world equipment condition and production security gates.

## M4 authenticated inventory live-change separation (draft PR #24)

Public static table QR remains unrelated to game authorization.
Only a valid active guest session or permitted waiter/staff session may
watch `topic=games`; private loan markers still use owner/visit
or visit-opening-waiter filters. Authenticated viewers of the
catalogue can observe generic game inventory changes even when they
do not own that loan, because the available count is visible in the
same catalogue. Outbox and browser SSE do not contain copy codes,
inspection reasons, staff credentials, guest identities or financial
data. A lost NOTIFY triggers the existing durable cursor catch-up.
No PII-bearing realtime inventory details are authorized.

## M4 manager game inventory search boundary (draft PR #25)

Serialized physical copy IDs and conditions remain privileged, never
available via a static table QR or a guest/waiter/cashier endpoint.
A new role-gated, no-store, server-paginated lookup validates search,
condition and page on the backend, uses prepared SQL arguments and
literal substring semantics to avoid wildcard injection, and limits
each response to 25 copies. GET filtering changes no physical
condition and grants no ability to bypass staff audit, copy locks,
damage reasons or guest loan ownership. Resource and query page
caps are explicit until real operational volume/load review.

## M4 audit history search restrictions (draft PR #26)

A new manager/admin-only, no-store condition-history search endpoint
does not expose staff inspection notes to waiter, cashier, kitchen,
guest or anonymous clients. Bound SQL arguments and literal substring
search prevent user queries from being evaluated as SQL expressions;
backend validates page/search/change independently of frontend.
Responses are capped at 25 records. Audit source rows remain append-only;
the UX cannot delete, edit, rewrite or interpret findings as customer
liability, and no automatic charge is generated. Historical notes may
contain operational free text; access and retention policies must be
reviewed before commercial deployment.

## M4 unit-loss incident controls (draft PR #27)

An OPEN missing-unit report does not change game stock, loan ownership or
payment; only authorized manager/admin can confirm actual loss.
Confirmation requires an exact server-verified copy code, a 5-300
printable-character review note, a live allocated loan and consistent
visit->loan->incident->copy locks. Once confirmed, the loan becomes LOST
and copy condition LOST, so assignment cannot reuse it or silently mark
it returned. Open reports block casual handover/return until inspected
manager dismissal; stale/replayed decisions fail. Immutable report and
manager decision rows plus audit persist separate from customer payment
records. Public and waiter loan SSE stays identity-scoped; incident
notes and identity never enter outbox JSON or SSE. No blame or charge is
inferred from loss; policy and retention reviews remain release blockers.


## M4 found physical game copy recovery boundary (draft PR #28)

LOST copies must never be returned through the direct inventory
condition mutation, guest loan, or waiter return routes. The new
manager/admin-only recovery route locks the serialized physical unit,
requires code identity match, a verified historical confirmed-loss
incident and a current LOST classification, and fails closed for
conflicting active assignments. It writes a permanent actor-attributed
LOST → DAMAGED condition event linked to the old loan, not a customer
payment/receipt action. Only a second independently confirmed
DAMAGED → AVAILABLE action can restock. Original LOST loans and
confirmed incident evidence are immutable; a recovered unit is not
proof of fault, theft, restitution or any compensation entitlement.


## M4 manager incident search authorization and workload (draft PR #29)

Loss reports contain operational free-text findings and staff details.
The new search/read endpoint remains strictly manager/admin-only
through Spring Security, no-store and server-side result verification.
Search uses bound SQL `POSITION` expressions (literal, not pattern
wildcards), validated query/status/page and a 25-row per-page cap.
`openCount` only reports number of OPEN records to authorized staff;
it does not expose report contents or unauthorized identifiers.
The existing authenticated game live stream already scopes supervisor
loan events to staff and refreshes manager pages. Search is read-only;
no inferred customer liability, collection, automatic fee, incident
rewrite, or data export is introduced. Real-world evidence retention
and appeals policies remain explicit release blockers.


## Manager-only landing alerts and privacy (draft PR #30)

`/api/v1/admin/games/loss-incidents/summary` is behind the existing
Spring Security MANAGER/ADMIN role gate, no-store responses and
server-side staff bearer. The Next.js landing page calls it ONLY after
verifying role. Unauthorized staff pages neither call nor render the
manager notice. The summary explicitly omits free-text inspection
notes and personally identifiable customer and payment data.
Browser SSE carries change markers from the existing authenticated
staff game feed, not complaint content; refresh reads authoritative
server state. Backend errors are surfaced as uncertain availability,
not a "0 unresolved reports" claim. No irreversible or financial
actions originate from the home alert.


## Attributed incident evidence access controls (draft PR #31)

Incident evidence is confidential operational staff data.
Both `/staff/games/incidents/{id}` and the supporting
`/api/v1/admin/games/loss-incidents/{id}/evidence` require a
verified MANAGER/ADMIN identity. The server-side fetch uses
authenticated staff bearer and no-store response. Backend access
reads only incident and allowlisted audit event columns, joining
reporter/reviewer/actor usernames, not guest, phone, payment or
`safe_before_after_diff` records. Arbitrary incident IDs cannot
cross this role gate. No delete/repair/update endpoints are added,
and the timeline is evidence of staff activity, **not proof of guest
fault**. Unavailable backend fails closed and never fabricates history.


## M4 mobile incident review usability (draft PR #32)

Responsive adjustments remain scoped under `.staff-shell`; no
guest token storage, authorization, action confirmation, backend
write path or public route is changed. Tap-safe confirm labels and
separated manager decision forms reduce accidental physical
status changes. Deep links only target existing role-gated
server-rendered sections, never elevate a waiter or cashier into
manager incident access. URLs keep only pre-existing query and
filter state; no raw report content or private audit references
are inserted into a new link. Mobile tests do not connect to
customer data or external cafe endpoints.


## M6 CI-only backup/restore safety controls (draft PR #33)

The restore rehearsal refuses to run without explicit `CI=true`
and `SCS_RESTORE_TEST_ISOLATED=yes`, the exact known disposable DB
name and three synthetic fixture signals; it never inserts fixture
data itself and will not overwrite an existing target database.
Its temporary PostgreSQL archive stays inside the disposable DB
container and is deleted on exit. The only database DROP targets a
verification DB this invocation created. Running the CI command
outside isolated CI or against merchant records is prohibited.
A real backup, even encrypted, is sensitive; production credentials,
billing records and customer PII must never be copied into this test.
The content hashes here are deterministic integrity smoke checks, supplemented by active CHECK/FK rejection tests in the restored database,
not a substitute for authenticated encrypted backups.


## M6 CI HEAD-tree secret screening and npm production advisory gate (draft PR #34)

A separate, fail-closed `security-baseline` GitHub Actions job
checks **all tracked text files in the checked-out tree** for
high-confidence credential formats: PEM private key headers, GitHub,
AWS, Google, GitLab, Slack, Stripe, and OpenAI key patterns.
Within first-party Next.js app/components/lib JavaScript/TypeScript,
it additionally blocks raw browser local/session storage access,
`dangerouslySetInnerHTML`, dynamic `eval` and `new Function`.
Findings report **only file, line and rule**, never source text or
matching secret material. Six synthetic test cases verify detection,
scope and non-disclosure, including a generated fake token.

The same job runs `npm audit --omit=dev --audit-level=high` against
the committed frontend lockfile and fails on known HIGH/CRITICAL
production JavaScript dependency advisories. Advisory registry
unavailability also fails the security job, rather than silently
claiming a pass. This is a **first automated security gate**, not
a full SAST solution: it cannot catch unrecognized/historical leaks,
text in binary blobs, compromised runtime dependencies, Java/Maven
vulnerabilities, dynamic flows, or browser/network misconfiguration.
No token should be assumed safe merely because this scanner is green.
CodeQL or equivalent backend SAST, full history scanning,
credential rotation policy and production release review remain open.


## M6 CodeQL and Maven OSV checks (stacked draft PR #35)

Two independent GitHub Actions security workflows cover **source-code
analysis** and **known Java dependency advisories** on top of the
existing npm/secrets checks. CodeQL runs `java-kotlin` (JDK 21,
instrumented Maven compilation, `mvn -B -ntp -DskipTests package`)
and `javascript-typescript` (buildless), both with extended
security queries. CodeQL uploads findings to GitHub Code Scanning;
a green workflow means the analysis completed, **not** that there
are zero security alerts. Alerts still require independent triage
and explicit review before any production release.

OSV-Scanner checks `backend/pom.xml`, resolving Maven's direct and
transitive production dependency graph using its supported resolver.
Its **PR comparison** checks for newly introduced vulnerabilities
relative to the PR's base, important for stacked draft PRs. Full,
fail-on-vulnerability scanning runs on pushes to `main` and a
weekly schedule, so new advisories affecting existing packages can
still raise a failed check. An OSV PR pass does **not** imply that
existing vulnerable packages are safe, and Maven test dependencies
may not be completely covered. Both workflows have explicit narrow
read access, granting `security-events:write` only to their scanning
jobs for result uploads; neither has deployment credentials.

Before public pilot, manually examine both tools' actual security
alerts, confirm applicability, set branch protection and vulnerability
response policies, and review container images, auth, payments,
privacy, logs, ingress and third-party integrations. Static
analysis cannot establish correct business authorization by itself.


## M6 Docker least-privilege application sandbox (stacked draft PR #36)

The local/CI Compose `api`, `web` and second `api_replica` now
run **with their pre-existing non-root image users** (`cafe` and
`node`) plus `read_only: true`, `cap_drop: [ALL]`,
`no-new-privileges:true` and `pids_limit: 512`. No
`privileged` mode, Docker socket mount or new external port is
introduced. The only writable app paths are bounded, volatile
`tmpfs` mounts: `/tmp` (64 MiB per service), and
`/app/.next/cache` (128 MiB) for the Next.js web service.
Tmpfs uses `nosuid,nodev` and mode `1777`; temporary/cache data
is lost after a restart. No bearer tokens or guest/staff secrets
are intentionally stored in this cache.

The existing PostgreSQL container and opt-in localhost Nginx test
proxy are **deliberately unchanged**: their entrypoint startup and
privileged-port behavior require separate review before imposing
non-root or read-only root filesystem constraints. This is an
application-container hardening increment, not image CVE scanning,
rootless Docker, encrypted secrets delivery, seccomp/AppArmor
profiling, production ingress review or proof of zero escape risk.

The executable `scripts/verify-container-sandbox.sh` checks the
Docker engine's **actual** security options, UID identity, effective
process limit and tmpfs mounts; it then verifies the app can write
to its temporary directory but **cannot** create a file under
`/app`. It checks the Next.js cache mount explicitly. Synthetic
clean-start and two-replica CI jobs run these checks against real
containers, while the remaining HTTP/HTTPS browser and Java tests
exercise normal application behavior.


## M6 runtime Docker image CVE triage and automated gate (stacked draft PR #37)

A dedicated GitHub Actions workflow now **builds the actual API and
web production runtime images** from their Dockerfiles on every PR,
main-branch push and weekly schedule. Trivy v0.76.1 examines
**OS + application-library** packages, reporting HIGH/CRITICAL
advisories, including those from the base images and transitive
runtime packages. Each image uploads SARIF to GitHub Code Scanning
under its own runtime category; findings remain discoverable even
when the pipeline fails.

The checked-in `scripts/inspect-image-vulnerabilities.mjs`
requires valid Trivy JSON and **fails CI** when HIGH/CRITICAL
vulnerabilities have a published `FixedVersion`. Five synthetic
tests assert both OS and library detections, report failures, and
the treatment of still-unfixed advisories. Entries without a
published fixed version **are not automatically waived**: they
stay in SARIF and need manual exploitability, exposure, mitigation
and acceptance review. Zero *fixable* findings is not proof
of zero vulnerabilities or production deployment readiness.

No registry push, public port exposure, credential provisioning,
production image rebuild or production database operation is
introduced. Build-stage dependencies, PostgreSQL/Nginx vendor
images, base-image digest pinning, SBOM retention, signed
provenance and supply-chain signing remain separate work.


## M5 staff access management increment (draft PR #40)

- GET /api/v1/admin/staff?page=N: manager/admin authorization, paginated at 25, no-store and no credential, phone, session or password data.
- POST /api/v1/admin/staff/{id}/status: reason-required optimistic before-state validation under row lock. Only ordinary non-privileged staff may be enabled/disabled. Neither manager nor admin can disable a manager/admin or self through this screen, preventing accidental privileged lockout. Invalid/replayed/stale transitions fail without audit duplication.
- Disable changes enabled to false, revokes all currently active sessions and writes an append-only audit operation with actor, resource, reason and state-only diff. Re-enable never unrevokes old tokens; fresh authentication is required.
- Backend denial and state tests, Chromium permission checks. Does not provision new staff, assign roles, handle password recovery or establish production MFA. Never use for public live operations before owner review.

## M5: staff onboarding restrictions (draft PR #41)

Only ADMIN may provision disabled WAITER/KITCHEN/CASHIER accounts. Managers may view and change enabled status of ordinary staff but cannot create new identities. This authority is validated by the backend service, not merely by hiding a form. Credentials use BCrypt and an ASCII byte limit to avoid bcrypt truncation. New users start disabled with no sessions and require a separate audited enable action; the plaintext password and digest are absent from JSON responses, log statements, audit diffs and redirects. Duplicate normalized login cannot overwrite an existing user. **This is development-only onboarding:** secure invitation or credential handoff, first-login password rotation, MFA, reset and privileged recovery require a separately approved policy before pilot.

## Staff password self-rotation (draft PR #42)

Self-service password changes require a valid staff bearer plus the existing
password. They enforce the same BCrypt-safe printable-ASCII 16–72 character
policy as provisioning, persistent per-account failed-current throttling and
single-transaction password update, all-session revocation and secret-free
audit. Login and rotation serialize on the staff row so a concurrent old
password login cannot survive a completed rotation. Browser secrets are
submitted only through same-origin Next.js Server Actions; no password or
token is placed in URLs, client storage or audit records. Initial-password
rotation is not yet mandatory and privileged recovery/MFA remain release gates.

## M3 fake gateway and attempt persistence boundary (draft PR #44)

A deterministic process-local `SandboxFakePaymentGateway` exists solely for tests and later sandbox integration. It is deliberately neither dependency-injected into production nor exposed through HTTP, and its displayed markers are explicitly non-payable. Returning `PENDING` or a locally expired attempt cannot and does not mutate payment_intents, orders, kitchen tickets, or receipts. A fake reference or any fake result is **never authoritative evidence of money**. V24 adds PostgreSQL idempotency, one-unresolved-attempt uniqueness, immutable identity/amount snapshots, cash-method mismatch denial and terminal-state transition guards. Confirmed payment event verification, signature/merchant/environment matching, durable event dedupe and reconciliation remain mandatory before enabling digital checkout. No real provider secret, real bank/VA number or customer's financial data is present.

## M3 sandbox attempt coordination (draft stacked PR)

The attempt coordinator is not injected or exposed by the running app and accepts only the in-process non-network-capable fake adapter. It validates guest and visit ownership against the DB, locks the authoritative payment intent row, checks ACTIVE/SUBMITTED/PENDING plus due=server total and amount_paid=0, and rejects cash methods or foreign identities. A separate key is blocked while any unresolved attempt exists. Expiry does not automatically settle or unlock an attempt without a reconciled status. Tests verify parallel same-key and competing-key races, foreign guest denial, cash denial, closed/settled order rejection and that amount_paid, receipts and kitchen tickets remain unchanged. A real provider must never be called from under these DB locks; external provider creation needs a reviewed two-phase/outbox protocol.

## M3 payment method fail-closed checkout (draft PR #46)

Digital checkout requires an explicitly configured sandbox environment AND a separate opt-in feature flag. Both are disabled by default. All server-authoritative checkout, stock, ownership, amount and idempotency checks run before a QRIS/VA PENDING order/intent can be committed. No fake payment status can mark an order PAID or admit kitchen tickets. The existing CASH method remains the default when paymentMethod is absent, with prior idempotency hashes and UI unaffected. Cash and sandbox method requests cannot share a key without a 409 conflict. The application does not call a fake/real gateway from checkout. Operators must never set the sandbox flags in a real cafe production deployment. This is **not** an approved public payment method, verification service or settlement workflow.

## M3 explicitly sandbox-only guest attempt HTTP surface (draft PR #47)

SandboxPaymentAttemptController is registered only with backend environment=sandbox via ConditionalOnProperty, and each method independently requires sandbox-digital-checkout-enabled=true. Outside sandbox its HTTP routes are absent. Every request authenticates a guest bearer under /api/v1/me/**; the coordinator then checks order guestId+visitId and intent method/status/amount within a locked transaction. GET history rechecks the same ownership without leaking idempotency keys; results and POST responses are no-store. The customer UI is opt-in sandbox-only, clearly warns that FAKE references cannot be paid and displays true PENDING/failed states from backend. No fake state is accepted as financial verification. Even an attacker forging a Next.js form cannot mark an order PAID or create a receipt: no settlement action exists. Production deployments must NEVER set both sandbox flags; payment gateway credentials, signed webhooks, verified reconciliation and secure release authorization remain blockers.

## M3 separate fake-payment browser CI isolation (draft PR #48)

The QRIS/VA UI end-to-end suite runs exclusively under E2E_SANDBOX_PAYMENTS=true and an isolated Docker Compose override with BOTH server-side sandbox properties enabled. The existing cash and HTTPS browser jobs do not inherit either property and skip the fake-payment test, proving the default production-shaped behavior remains cash-only. The new job uses its own disposable DB, synthetic identities, no merchant credentials and no real payable instructions. Cross-guest read/create attempts and duplicate fake attempt keys are exercised through real authenticated requests. A post-browser SQL gate independently checks zero paid digital money, zero digital receipts and zero digital kitchen ticket release. The entire ephemeral database is deleted in the job's unconditional teardown.

## M3 fake-event inbox anti-replay and no-settlement boundary (draft PR #49)

The synthetic event inbox is deliberately **not externally reachable** and accepts no real provider callback. Records are bounded canonical metadata with a SHA-256 payload comparison fingerprint; a hash is NOT provider authentication. PostgreSQL enforces immutable event rows, strict fake provider namespace, unique provider event ID and legal classification values. Duplicate events with identical canonical fields return the original durable evidence, including concurrent replays; event ID reuse with changed reference/amount/status/environment is 409. References never grant ownership or authority. A fake SUCCEEDED notification only yields QUARANTINED evidence and never marks payment PAID. Wrong currency/amount/method, wrong environment, unknown attempt reference, late success and contradictory terminal status each receive explicit classifications for future manual review, with no silent state change. Full real payment security requires provider-specific cryptographic verification, merchant/environment/reference reconciliation, durable source audit and status inquiry BEFORE any eventual payment state transition.

## M3 sandbox reconciliation review boundary (draft PR #50)

Untrusted synthetic callback evidence remains permanently quarantined in V25. A new **read-only** query endpoint and staff view permit admins to inspect the immutable records and classify anomalies without financial side effects. Its Spring controller is absent outside the sandbox environment, checks a second feature flag, then checks the authenticated ADMIN role even though the enclosing /api/v1/admin/** routing includes MANAGER. Default environments expose no review endpoint or UI. GET is no-store and paginated to at most 20 evidence records per page; filtering is allowlisted with bound SQL parameters, not interpolated SQL. No event write/approval/reset action is introduced. The browser never receives raw payment credentials or payload fingerprints. A fake SUCCEEDED claim remains UNTRUSTED regardless of whether its fields match a pending attempt; verified provider authentication and settlement reconciliation are still release blockers.

## M3 signed-only synthetic event intake (draft PR #51)

The new FAKE callback receiver is conditionally registered **only** for sandbox deployments. A second independent feature flag must be on. If the test signing key is unset or under 32 UTF-8 bytes, intake fails 503 before processing. Strict timestamp freshness of five minutes, bounded body size (4096 bytes), rigid lowercase signature syntax, and constant-time HMAC comparison over the *unmodified bytes* block missing/stale/tampered calls. Signature verification runs before JSON decoding or any DB operation. Duplicate identities are resolved by the existing V25 immutable event inbox; changed signed content on a reused event ID produces 409. Every accepted FAKE SUCCEEDED claim is **still untrusted quarantined evidence**, not financial approval; the existing readonly admin dashboard remains the review-only surface. This local HMAC protocol is deliberately NOT Midtrans/provider compatible. No real money, provider authorization, merchant scope, settlement, ticket, receipt, callback proxy or production ingress is implemented. A public gateway will require official provider signature rules, network authentication and independent status-API reconciliation before any paid transition.

## M3 append-only sandbox triage audit (draft PR #52)

Administrators can label synthetic quarantined evidence as under investigation, requiring provider review, or escalated, via a sandbox-only endpoint. Every POST revalidates ADMIN role and the independent sandbox flag, locks the corresponding immutable evidence row to serialize concurrent requests and writes one signed-in actor ID with a constrained action code to V26. A duplicate key for the same actor/code is idempotent; conflicting reuse fails closed. The database rejects mutation or deletion of the triage history. Controlled action codes deliberately exclude APPROVE, VERIFIED, PAID, SETTLED and RESOLVED. No free-text notes, raw notification, secrets, customer PII or real payment identifiers enter the audit. Frontend controls are display-only security, backed by server authorization. Cash and real merchant payment states remain unchanged, and the immutable evidence cannot be edited.
