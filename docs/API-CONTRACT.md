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
