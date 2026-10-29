# M2 Guest Ordering Browser (Draft PR #5)

**Scope:** Customer ordering UI stacked on PR #4 → #3 → #2 → #1. This is a development-only slice, not approved for public deployment or real cash.

## Implemented customer journey

1. Static QR/NFC alias `/t/[tableAlias]` looks up public table metadata, not a credential.
2. An active table visit and a fresh staff-generated, short-lived join proof are both required. Guest supplies nickname and proof in a POST form. No account, phone or installation required.
3. Spring Boot creates a revocable visit-scoped guest token; the web server stores it in a path-scoped HttpOnly, SameSite=Strict cookie with Secure in production. No bearer in client JavaScript, URL or localStorage.
4. `/visit` lists available menu items; `/visit/menu/[itemId]` loads required/optional single-select modifier groups. Up to eight groups per item, validated by backend. Adding the same item replaces its prior configuration because separate customized duplicate lines are not supported.
5. `/visit/cart` uses the guest's persisted versioned cart (GET and PUT) with backend-validated availability/price and optimistic conflict handling.
6. Cash checkout submits only `cartVersion` plus a per-form idempotency key to `POST /api/v1/orders`. The server owns stock, final totals, ownership and duplicate prevention. It creates AWAITING_CASH, not PAID.
7. `/visit/orders` and `/visit/orders/[id]` show guest-owned order lifecycle and payment state separately. A paid order can retrieve its immutable receipt from `GET /api/v1/me/orders/{id}/receipt`.
8. `POST /api/v1/me/logout` revokes the backend guest session; the browser clears the cookie even if backend revocation is unavailable, and explicitly warns when revocation cannot be confirmed.

## Test coverage

- Existing integration suite verifies guest ownership and price/stock/idempotency guards.
- New assertions cover prepayment receipt denial (404), cross-guest receipt denial (404), paid receipt access (200), no-store receipt response and revocation after guest logout (401).
- CI Compose smoke checks anonymous guest routes, unknown table alias and unauthenticated receipt denial, in addition to M1-M2 staff smoke.
- Final-head CI is required before review. Authenticated real browser E2E has **not** been demonstrated.

## Required remaining work

- Browser E2E with synthetic active table/join proof, multi-group modifiers, cart stale-version, checkout replay, OCC, KDS, waiter, physical cash tender and receipt.
- IP/device join throttling, secure-origin CSRF/proxy checks, guest cookie behavior across real browsers, accessibility and mobile QA.
- Staff table-management and join-proof UI, session recovery, multi-customization lines, SSE, game inventory, QRIS/VA sandbox, optional phone receipt/consent.
- No live money, real customer PII or external messages. Public deployment remains blocked.

## Rollback

No new DB migration. Revert PR #5 to preserve preceding staff/backend capabilities. Finalized order snapshots and financial history remain immutable.
