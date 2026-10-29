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
