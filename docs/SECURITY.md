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
