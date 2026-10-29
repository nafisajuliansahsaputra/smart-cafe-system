# M2 staff browser integration — draft, not deployed

**Branch:** `feat/m2-staff-oc-browser` stacked on M2 PR #2, itself stacked on M1 PR #1.
**Scope:** Staff web sign-in and first actionable Order Control Center. Backend remains the authoritative service.

## Routes

- `GET /staff/login`: credential form; Next.js Server Action calls `POST /api/v1/staff/auth/login` from the server, sets an HttpOnly cookie on success and redirects.
- `GET /staff`: verifies the revocable backend session through `/staff/auth/me` on every request, shows staff roles. OCC navigation appears only for CASHIER/MANAGER/ADMIN.
- `GET /staff/orders`: protected server-rendered GET of `/ops/orders`, filtering by order/payment status and table, manual refresh, 100-row backend ceiling.
- `GET /staff/orders/[id]`: protected itemized backend detail with separate order/payment states; pending cash orders can be approved or rejected with a required reason.
- `POST` via Next.js Server Actions: backend commands are fixed-path and authenticate as the current staff member. State transitions, stock release, audit trail, kitchen admission and authorization remain entirely in Spring Boot.

## Security boundaries

- Raw backend bearer tokens do not enter HTML, client components, URLs, localStorage, server logs or Next.js props.
- Auth cookie is `HttpOnly`, `SameSite=Strict`, `Secure` in production, limited to `/staff`, and expires no later than the backend 8-hour session.
- Logout calls backend revocation, expires the **same path-scoped** cookie and gives a warning if server-side revocation cannot be confirmed.
- Protected screens call the backend to re-check current staff identity and roles, including disabled users. A missing/stale session redirects to login; upstream failure shows a fail-closed error, not privileged mock content.
- Only predefined endpoints are reachable through the server helpers. No general-purpose credential-forwarding proxy or browser Authorization headers.
- Next.js Server Actions enforce same-origin checks and the cookie uses strict same-site policy. Independently verify proxy/Origin/Host behavior and CSRF at the actual deployment origin.
- Staff route responses get `Cache-Control: private, no-store`, `Referrer-Policy: no-referrer` and `X-Frame-Options: DENY`.
- Real operator use requires TLS, secure deployment/session review, rate-limit verification and full authenticated E2E/CSRF testing.

## Local operator acceptance (manual steps, not yet executed)

1. Bring up the stacked app with `docker compose up --build` and opt-in local bootstrap (see M2-LOCAL-BOOTSTRAP). Remove the one-time bootstrap variables and restart first.
2. Unauthenticated `/staff/orders` redirects to `/staff/login`. No order rows or auth token appear in the rendered page. CI smoke covers anonymous login render and protected redirect.
3. Wrong credentials show a generic error. Five failed attempts enforce the existing backend throttle. No credentials appear in the URL.
4. A logged-in cashier/manager/admin sees `/staff/orders`. Kitchen/waiter-only identities cannot load OCC, even by manual URL or forged form input.
5. Create a **synthetic** table visit and cash order using M2 API fixture flow. OCC shows `SUBMITTED` and `AWAITING_CASH` distinctly. Filters and detail show snapshot prices and modifiers.
6. Approve one pending cash order: backend creates an admitted kitchen ticket, order becomes ACCEPTED; payment remains `AWAITING_CASH`. Refresh confirms DB truth.
7. Reject a different submitted cash order with 4–300 character reason: backend audits, releases stock once and rejects repeat/conflicting actions.
8. Disable staff server-side while logged in: next protected navigation/action fails authorization. Logout revokes session and removes path-scoped cookie; browser Back must not reveal a cached protected page.
9. Disconnect API during navigation/action: fail closed with service error, no fabricated success. Restore and refresh from authoritative backend.

## Verification ledger

- Existing PR #2 CI covered PostgreSQL backend state transitions and role/ownership checks.
- PR #3 introduces additional Compose smoke assertions for anonymous login page and redirect from `/staff/orders`. Final-head GitHub Actions results must be checked before approval.
- **Not yet verified:** authenticated browser E2E, real proxy/CSRF behavior, cross-browser secure-cookie behavior, accessibility audit, manual reconciliation and cash collection UI.
- No payments provider, external receipt sender, customer PII or public deployment is enabled.

## Rollback

Revert or close PR #3 without altering backend schema or cash/payment records. No database migration is added. Clear server/browser session cookies when rolling back the web UI. PR #2 backend remains independently testable via API.
