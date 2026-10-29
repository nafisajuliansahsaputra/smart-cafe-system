# API Contract — Smart Cafe System v1 (Proposed)

**Status:** M2 backend subset implemented on draft development PR #2, **not merged, deployed or production-ready**. Routes below remain the target contract; only the subset explicitly listed in the implementation ledger is available.

Base: /api/v1. JSON over HTTPS. Timestamp RFC3339 UTC. Money as integer IDR. IDs opaque UUID/ULID string. Staff and guest authentication differ. AuthZ enforced in API and domain services.

## 1. Consistent API envelope

Success example:

~~~json
{"data":{"id":"order-id","status":"SUBMITTED"},"meta":{"requestId":"request-id"}}
~~~

Error example:

~~~json
{"error":{"code":"SESSION_CLOSED","message":"This table visit has ended","requestId":"request-id","details":{}}}
~~~

Expected error codes: VALIDATION_ERROR (400), UNAUTHENTICATED (401), FORBIDDEN (403), NOT_FOUND (404), SESSION_CLOSED (409), CONFLICT (409), OUT_OF_STOCK (409), PAYMENT_PENDING (409), RATE_LIMITED (429), PROVIDER_UNAVAILABLE (503). Avoid leaking resource existence cross-owner.

Use Idempotency-Key for any checkout/cash capture/financial command and include safe conflict behavior for reused keys with changed payload.

## 2. Guest table / visit

| Method | Path | Access | Description |
| --- | --- | --- | --- |
| GET | /tables/{alias}/entry | public | Safe table metadata, join instructions; **no session secret** |
| POST | /table-visits/join | public + join proof | Join active table visit and create scoped guest credential |
| GET | /me/visit | guest | Current visit/table, own identity |
| GET | /menu | public/guest | Available catalog with variants |
| GET | /games | guest | Physical game categories and availability estimate |
| GET | /me/cart | guest | Own cart |
| PUT | /me/cart | guest | Validate/replace own cart lines |
| POST | /orders | guest | Server-priced idempotent checkout |
| GET | /me/orders | guest | Own orders (paginated) |
| GET | /me/orders/{id} | guest owner | Own detail and status |
| POST | /orders/{id}/payment-attempts | guest owner | Start/retry digital payment when allowed |
| GET | /me/receipts/{id} | guest owner | Finalized receipt (not prepayment) |
| GET | /me/events | guest | SSE status hints; snapshot refetch |

Customer orders cannot reference another visit/guest. If guest sends table_id, backend ignores untrusted selection and derives from authorization.

## 3. Protected Order Control Center

**Required** cashier/manager role. Waiter may see a separately filtered task surface, not full privileged OCC by default.

| Method | Path | Access | Description |
| --- | --- | --- | --- |
| GET | /ops/orders | cashier/manager | Filter by table, date, status, payment, order ID |
| GET | /ops/orders/{id} | cashier/manager | Itemized order, payment attempts and audit-safe details |
| POST | /ops/orders/{id}/approve-cash | cashier/manager; waiter by policy | Approve real visit and admit cash order to kitchen |
| POST | /ops/orders/{id}/reject | cashier/manager | Reject invalid pre-prep order |
| POST | /ops/orders/{id}/cancel | manager/approved cashier | Conditional cancellation with reason |
| POST | /ops/payments/{intentId}/cash-tenders | cashier/authorized waiter | Record actual cash received (idempotent) |
| POST | /ops/payments/{intentId}/reconcile | cashier/manager | Trigger safe verification workflow |
| POST | /ops/payments/{intentId}/refunds | manager or separately granted permission | Controlled refund, no direct toggle |
| GET | /ops/events | authorized staff | Filtered SSE events, always refetch snapshot |
| GET | /ops/reconciliation | cashier/manager | Unresolved cash/digital payment exceptions |

Tender payload includes amount received and request key; calculate change server-side and store timestamp/actor. Refund requests need reason, entitlement and gateway/policy validation.

## 4. Kitchen, waiter, admin

| Method | Path | Access | Description |
| --- | --- | --- | --- |
| GET | /kitchen/tickets | kitchen | Eligible tickets only |
| POST | /kitchen/tickets/{id}/start | kitchen | ACCEPTED -> PREPARING |
| POST | /kitchen/tickets/{id}/ready | kitchen | PREPARING -> READY |
| GET | /waiter/tasks | waiter | Delivery/game/cash tasks |
| GET | /waiter/tables | waiter | Table occupancy |
| POST | /waiter/table-visits | waiter/cashier | Open active visit, generate join proof |
| POST | /waiter/table-visits/{id}/close | waiter/cashier | Close after obligations check |
| POST | /waiter/orders/{id}/served | waiter | Confirm actual food handover |
| POST | /waiter/games/{loanId}/deliver | waiter | Confirm physical unit handed over |
| POST | /waiter/games/{loanId}/return | waiter | Condition check and return |
| GET/POST/PATCH | /admin/menu-items | manager/admin | Catalog and availability |
| GET/POST/PATCH | /admin/game-copies | manager/admin | Serialized board-game inventory |
| GET/POST/PATCH | /admin/staff | manager/admin | Staff lifecycle and permissions |
| GET | /admin/audit | manager/admin | Audited operations with scope and pagination |

Route assignments are a contract target, not generated framework code.

## 5. Game request lifecycle

| Method | Path | Access | Description |
| --- | --- | --- | --- |
| POST | /me/game-requests | guest | Request physical game during active visit |
| GET | /me/game-requests | guest | Own outstanding requests |
| POST | /waiter/game-requests/{id}/assign | waiter | Assign available copy transactionally |
| POST | /waiter/game-requests/{id}/decline | waiter | Explain unavailable request |
| POST | /waiter/games/{loanId}/return | waiter | Confirm return and unit condition |

If checkout includes a free-game request, make it a separate bound request; do not block paid food order success on unreserved game allocation. Paid rental is deferred pending explicit compensation policy.

## 6. Provider webhooks and receipt delivery

| Method | Path | Access | Description |
| --- | --- | --- | --- |
| POST | /integrations/midtrans/webhook | provider | Verify signature, order, amount, status; dedupe |
| GET | /receipts/{accessToken} | bearer token | Expiring scoped share access, rate-limited and masked |
| POST | /me/receipt-preferences | guest | Set channel, destination and opt-in consent |
| POST | /me/receipts/{id}/send | guest owner | Request allowed resend with throttle |
| GET | /ops/receipt-dispatches | cashier/manager | Delivery status without broad phone disclosure |

Public receipt token is secret capability; must be unguessable, short-lived, hashed in DB, not placed in analytics logs or referrers. Provider-specific URL and message template are deferred until ADR/provider decision.

## 7. Events and replay

Example SSE (not truth in place of GET):

~~~text
event: order.updated
id: event-id
data: {"orderId":"order-id","status":"READY","version":6}
~~~

On reconnect, client must fetch canonical snapshot and resubscribe. Staff SSE subscription filters by allowed scope; never send phone/payment secrets to kitchen.

## 8. Contract test matrix

- Guest cannot query another guest's order using ID.
- Staff role cannot call privileged refund outside permission.
- Checkout replay with same key returns same order; same key and changed request rejects.
- Double cash tender rejected or returns identical result, never duplicate PAID.
- Webhook invalid signature/amount/order rejected; genuine duplicate a no-op.
- SSE reconnect resolves missed event by snapshot.
- Invalid/expired receipt bearer returns safe 404/410, no phone leak.

## 9. Implemented backend ledger (M2 draft PR #2)

Implemented and verified with PostgreSQL Testcontainers/MockMvc:

| Path | Current implementation | Remaining |
| --- | --- | --- |
| POST /staff/auth/login, GET /staff/auth/me, POST /staff/auth/logout | bcrypt staff credentials, revocable 8-hour bearer sessions, DB-backed failed-login throttling, fresh role checks | Secure browser/BFF UX, full staff management/MFA |
| GET /tables/{alias}/entry | Public table metadata only, no join secret | Customer QR entry UI |
| POST /waiter/table-visits, POST /waiter/table-visits/{id}/join-proof, POST /waiter/table-visits/{id}/close | Staff-authorized open, rotating 10-min proof, close guard for unresolved cash, session revocation and staff audit | Table CRUD, assignment policy, game-loan closure guard when games are implemented |
| POST /table-visits/join, GET /me/visit | Active-visit verified proof, isolated guest and revocable guest bearer | Guest join UI and join abuse throttling |
| GET /menu | Active categories and available base-price items | Variants, limited stock and catalog admin |
| POST /orders, GET /me/orders | Guest-owned cash order, 1–30 unique lines, integer IDR server prices, immutable order snapshots, PostgreSQL idempotency lock | Persisted cart, variants/taxes, rich customer UI |
| GET /ops/orders, POST /ops/orders/{id}/approve-cash | Authenticated cashier/manager filtered queue and itemized order detail, audited cash approval, kitchen admission | Filters/detail/reject/cancel/exception UI |
| GET /kitchen/tickets, POST /kitchen/tickets/{id}/start, POST /kitchen/tickets/{id}/ready | Scoped kitchen reads, guarded ticket/order transitions | Actual kitchen display browser UI |
| POST /waiter/orders/{id}/served | Staff-confirmed serving, does not change payment status | Waiter task UI and delivery assignment |
| POST /ops/payments/{intentId}/cash-tenders | Waiter/cashier/manager/admin only; amount/role/state validated, idempotent recorded tender, change calculated, immutable paid receipt | Reconciliation, exceptions, cashier shift management |
| GET /me/receipts/{id} | Paid only, owner-scoped receipt projection, no phone number | Receipt print/download styling and messaging |

All remaining routes in sections 1–8 are **unimplemented**. The initial checkout accepts a bounded lines payload because persisted guest carts have not yet been added. No QRIS/VA gateway is connected and `PENDING` never implies paid.

M2 demo currently requires the order to be **SERVED** before recording cash tender. This is a deliberately conservative sandbox behavior, **not a settled merchant payment policy**; the owner must confirm acceptable cash collection points before a production pilot. Tax/fee calculation is disabled for synthetic demonstration orders with explicit `M2-DEMO-UNTAXED` pricing version and is not a legal-tax policy.

**Staff access refinement (M2):** A waiter can close/rotate, serve and capture cash only for a visit the waiter opened. This temporary conservative rule will be replaced by actual task assignment. Query filters are parameterized and enum-validated; staff access to itemized OCC detail requires cashier/manager/admin privileges.

## 10. Cart, limited item stock and rejection (M2 draft extension)

- `GET /api/v1/me/cart` requires a scoped active guest bearer; returns `{version,estimatedSubtotalIdr,lines[]}`. Each line exposes current server item price, total, whether orderable now, and a nullable remaining quantity. `Cache-Control: no-store`.
- `PUT /api/v1/me/cart` accepts `{"expectedVersion":0,"lines":[{"menuItemId":"uuid","quantity":2}]}`. Lines are unique; max 30, each quantity 1–20. A stale cart version is **409 CONFLICT**. Empty array clears cart. The server never accepts user-submitted price.
- `POST /api/v1/orders` accepts either `{"lines":[...]}` (existing direct cash checkout) **or** `{"cartVersion":1}` (checkout from stored cart), but not both; an `Idempotency-Key` is required. Cart-based checkout consumes the cart and increments its version after success. A replay of the same key and version returns the same stored order even after the cart is cleared; a different version with the same key conflicts.
- `PATCH /api/v1/admin/menu-items/{id}/availability` requires MANAGER or ADMIN and accepts `{"available":true,"limitedQuantity":3,"expectedVersion":0,"reason":"Stock count corrected"}`. A `null` quantity explicitly means unlimited; zero means sold out. The write is version checked and audited. `GET` on the same path returns the current stock/version.
- `POST /api/v1/ops/orders/{id}/reject` requires CASHIER/MANAGER/ADMIN and `{"reason":"..."}`. It accepts only an unapproved SUBMITTED cash order still AWAITING_CASH and with no kitchen ticket; it atomically rejects the order and releases its tracked stock allocation once. A second rejection is 409.

The public `GET /menu` hides items with zero or disabled availability. All claims are bounded to single-cafe development with synthetic data, not production inventory/checkout policy.

### Additional guest read and outbox event

`GET /api/v1/me/orders/{id}` is implemented with ownership and visit checks in SQL. The response includes current order/aggregate-payment status, immutable line-item prices/quantities and creation timestamp. An unrelated guest gets a safe 404. On the successful first checkout, `ORDER_SUBMITTED` is committed to the existing durable outbox in the same PostgreSQL transaction. This does not mean SSE transport or worker dispatch is yet enabled.

## 11. Development-only catalog and variant endpoints

All paths are under `/api/v1`. Administrative routes require MANAGER/ADMIN bearer authentication and record staff audit entries.

| Method | Route | Payload |
| --- | --- | --- |
| GET/POST | /admin/menu-categories | POST `{name,slug}`; slug is unique lowercase kebab-case |
| PATCH | /admin/menu-categories/{id}/active | `{active:boolean}` |
| GET/POST | /admin/menu-items | POST `{categoryId,name,priceIdr}`; new items start HIDDEN |
| GET/PATCH | /admin/menu-items/{id} | PATCH `{categoryId,name,priceIdr,availability,expectedRevision}` |
| GET | /menu/{itemId}/variants | Public active group/options for visible stocked menu item only |
| GET/POST | /admin/menu-items/{itemId}/variant-group | POST `{name,required}`; maximum one group per item |
| PATCH | /admin/variant-groups/{id} | `{name,required,active,expectedRevision}` |
| POST | /admin/variant-groups/{id}/options | `{key,label,priceDeltaIdr}` |
| PATCH | /admin/variant-options/{id} | `{label,priceDeltaIdr,active,expectedRevision}` |

M2 supports **one selected option at most per menu item**, not arbitrary multiple modifiers or separate stocks per variant. Cart line and direct checkout line accept optional `variantOptionId`; if an item's enabled group is required, it must be supplied. It must reference an enabled option belonging to that item. The backend computes the final unit price using current base price + server-stored signed option delta; clients cannot submit a price. Snapshot stores option ID, group name, option key/label and delta as JSON in the immutable order line. Replaying the same idempotency key with a different direct-checkout option conflicts.

## 12. Multi-group modifier upgrade (Flyway V9)

New public `GET /api/v1/menu/{itemId}/variant-groups` returns an array of every **active** group, with active options only, on available menu items. New manager/admin `GET /api/v1/admin/menu-items/{itemId}/variant-groups` returns active/inactive configuration for review. Existing `POST /admin/menu-items/{itemId}/variant-group` can add groups, bounded to eight per item, and existing update/option CRUD routes remain valid. An item can have multiple different required/optional single-select groups.

Cart `PUT /me/cart` and direct `POST /orders` lines now accept `variantOptionIds: [uuid, ...]` (zero to eight unique UUIDs) in addition to the backwards-compatible singular `variantOptionId`. **Do not send both fields in one line.** Empty/missing array means no choices; any active required group then causes 409. Duplicate choices, multiple choices in one group, inactive/foreign options or a negative/overflow adjusted price are rejected server-side. The complete option UUID list is sorted for direct checkout idempotency so reversing an array does not create a distinct order.

The singular public legacy route `/menu/{id}/variants` remains for one-group items, but returns 409 for a multi-group item rather than hiding other required choices. M2 still stores one cart line per menu-item ID and uses parent menu item stock quantities, not modifier-level inventory. Order line `variant_snapshot` includes immutable `label` and `options[]` metadata. Receipt/KDS/guest/OCC read the snapshot label, never a live option name.


## M2 fulfillment UI follow-up (stacked development PR #4)

`GET /api/v1/waiter/orders/tasks` returns actionable order deliveries and cash tasks. Each row has `orderId`, `paymentIntentId`, `tableCode`, `orderStatus`, `paymentStatus`, `totalIdr`, `createdAt`, safe snapshotted lines, and nullable `receivedIdr`/`changeGivenIdr` sourced from a recorded cash tender. This endpoint is **not public**, and waiter-only actors see orders exclusively for visits they opened. Cashier/manager/admin see supervisor scope. Kitchen and guests are forbidden. Once served and paid, the record remains visible for up to one day by order creation timestamp for handover verification.

`/staff/kitchen` consumes existing `GET /kitchen/tickets`, `POST /kitchen/tickets/{id}/start` and `POST /kitchen/tickets/{id}/ready`. `/staff/waiter` consumes `GET /waiter/orders/tasks`, `POST /waiter/orders/{id}/served`, `POST /ops/payments/{intentId}/cash-tenders` with a fresh idempotency key. All browser commands are server-side, scoped to the current backend-authenticated staff session and require confirmation. The backend, not the browser, remains responsible for financial and fulfillment state.

M2 limits remain: delivery allocation still binds the table-opening waiter (no reassignment workflow); cash-at-handover is a provisional demo rule; task refresh is manual, not SSE. Do not enable actual cash or customer data in a publicly deployed environment.

## M2 guest customer browser follow-up (draft PR #5)

Implemented new guest-authenticated endpoints:
- `POST /api/v1/me/logout` revokes current token by hash, guest ID and visit ID.
- `GET /api/v1/me/orders/{id}/receipt` returns finalized immutable receipt only for the current owning guest/visit and confirmed PAID payment. Other guests and unpaid orders receive safe 404; response is no-store.

Browser routes: `/t/[tableAlias]` for public table metadata plus short-lived staff join proof, `/visit` catalog, `/visit/menu/[itemId]` modifiers, `/visit/cart` versioned cart, `/visit/orders` history and `/visit/orders/[id]` tracking/paid receipt. Authentication uses a path-scoped HttpOnly SameSite Strict cookie sent only to the Next.js web server. Backend still requires explicit bearer header, enforces ownership and finalizes cash payments.
