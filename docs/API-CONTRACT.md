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

## M2 table management endpoint (stacked draft PR #6)

`GET /api/v1/waiter/tables` is authorized for WAITER, CASHIER, MANAGER, ADMIN only. Returns up to 250 table records: `tableId`, `tableCode`, `publicAlias`, `capacity`, `tableStatus`, `activeVisit`, nullable `manageableVisitId`, nullable `proofExpiresAt`. For a regular waiter, the latter two fields are present only for a visit they opened; supervisors can see them for all active visits. There is **no join proof in GET responses**. An occupied table remains visible without its sensitive visit identifier to unrelated waiters. The response uses `Cache-Control: no-store`.

The existing POST operations `/api/v1/waiter/table-visits`, `/{id}/join-proof`, and `/{id}/close` are called from protected Next.js Server Actions at `/staff/tables`. Their backend role, ownership, conflict, settlement and guest-token-revocation checks are unchanged. The freshly issued proof is displayed only in the authenticated staff action result, never in a URL/QR or database-readable list.

## M2 public guest join throttling (stacked PR #7)

`POST /api/v1/table-visits/join` may now return `429 Too Many Requests` with `error.code=RATE_LIMITED`, a safe generic message, `Retry-After: 600` (seconds), and `Cache-Control: no-store`. The browser maps that response to a generic Indonesian "try again in about 10 minutes" message. No guest credential is issued. A 10-minute fixed window is shared via PostgreSQL; counters commit before checking the join proof.

Throttling uses the backend socket peer and table alias, not an untrusted client IP header. Behind a shared Next.js BFF it cannot accurately distinguish individual guests. A trusted edge rate limiter remains required for public launch.

## BFF-only SSE (draft PR #11)

GET `/staff/live?topic=orders|kitchen|waiter|tables` or `?topic=order&id=<uuid>` uses staff cookie. GET `/visit/live?topic=orders` or `?topic=order&id=<uuid>` uses guest cookie. Unknown topics/extraneous fields/invalid UUID → 400; no session → 401; backend forbidden/foreign order → 403/404. On 200, `text/event-stream` contains only empty `ready`, `changed`, `unavailable`, `auth-expired`, and heartbeat frames, with no-store, no-transform and no buffering. Each poll is re-authorized and the connection is bounded to 55 seconds.

## Live outbox transport (draft PR #12)

Backend internal database schema V11 adds `outbox_events.live_published_at` and `live_order_signals(sequence_id,outbox_id,order_id,event_type,published_at)`. A scheduled internal worker publishes via transactional `pg_notify('scs_order_live', order_uuid)` only after inserting a unique durable event row. **No new public HTTP endpoint or user-facing event contract is introduced in this PR.** Existing `/visit/live` and `/staff/live` preserve their PR #11 authenticated snapshot SSE contract until a scoped cursor subscriber is built and tested.

## M2 durable live backend cursor (draft PR #13)

Role-guarded backend GET routes `/api/v1/me/live-cursor?topic=orders|order&orderId=<uuid>`, `/api/v1/ops/live-cursor?topic=orders|order&orderId=<uuid>`, `/api/v1/kitchen/live-cursor?topic=kitchen` and `/api/v1/waiter/live-cursor?topic=waiter` now return `ApiEnvelope.data.cursor` (nonnegative signed-long). Optional `after=<cursor>` waits up to 14 seconds for a scoped change; immediate read without after. Role/ownership unauthorized → 401/403/404, invalid topic or negative cursor → 400, occupied long-poll capacity → 429. This endpoint is server-only and a raw global cursor is never exposed anonymously. Existing browser SSE event payload remains empty `{}` and unchanged; Next BFF does not forward cursor numbers to browser. Lost NOTIFY is recovered via durable query.

## TABLE_VISIT durable live cursor (draft PR #14)

`GET /api/v1/waiter/live-cursor?topic=tables` uses an existing role-checked waiter/cashier/manager/admin Bearer token and optionally `after=<cursor>`. Immediate read returns a scoped cursor; `after` blocks up to 14 seconds subject to existing 48-request admission control. All waiters observe open/close occupancy; a rotation event advances the cursor only for the opening waiter and supervisor. `/staff/live?topic=tables` uses the HttpOnly-cookie BFF bridge and sends empty `changed` SSE notifications, not secret proof or raw cursor.

## M4 physical game inventory and serialized loan endpoints (draft PR #19)

Guest JWT-like opaque Bearer (no login account) required for GET /api/v1/me/games, GET /api/v1/me/game-loans, POST /api/v1/me/game-loans `{gameId:uuid}`. Guest loans are scoped to the current guest+visit; REQUESTED has no unit allocation. Staff WAITER/CASHIER/MANAGER/ADMIN required for GET /api/v1/waiter/games, GET /api/v1/waiter/game-loans and POST /api/v1/waiter/game-loans/{id}/assign|handover|return|cancel; ownership of opening waiter is enforced for non-supervisors. Handover/return require `{copyCode:string}` and direct physical confirmation in staff UI. Only manager/admin may GET/POST /api/v1/admin/games, POST /api/v1/admin/games/{id}/copies. Success status 200 with ApiEnvelope.data. Invalid inputs 400; unauthorized 401; wrong role/ownership 403; missing IDs 404; invalid transition, duplicate request or exhausted physical copies 409. GET game and loan responses no-store. Table close returns 409 when any REQUESTED/ASSIGNED/IN_USE game loan remains. No fees, deposits or digital rental; current UI uses explicit refresh rather than SSE.


## M4 physical game live-change cursor contract (draft PR #20)

Game loan transitions (REQUESTED, ASSIGNED, HANDED_OVER, RETURNED, CANCELLED)
commit a safe GAME_LOAN outbox row in the same transaction and publish into
V14 `live_game_signals` through the existing durable asynchronous relay.
No game copies, guest details, passwords, or payment amounts appear in SSE.

`GET /api/v1/me/live-cursor?topic=games` and
`GET /api/v1/waiter/live-cursor?topic=games` both support optional
`after=<nonnegative long>` with the established bounded 14-second wait.
No `orderId` is accepted for this topic. Guest event cursors cover **only**
loans owned by that guest **in that visit**, even if others share a table.
Non-supervisor waiter cursors cover only loans of visits they personally
opened; cashier/manager/admin use an aggregate cursor. Kitchen and
unauthorized roles fail closed. Existing 401/403/400/429 boundaries apply.

The BFF permits exactly `/visit/live?topic=games` and
`/staff/live?topic=games` with a same-origin session cookie and no unknown
query parameters. The BFF's backend Bearer/cursor is not exposed. Browser
receives empty `ready`/`changed`/heartbeat frames and retrieves the
authoritative game list again. A declined network connection does not
change loan state or imply handover. Game *catalogue-only* inserts do not
yet publish this event family and can still require manual refresh.

## Guest game pickup request (stacked draft PR #21)

POST `/api/v1/me/game-loans/{id}/request-return` requires an active,
authenticated guest who owns that exact loan. IN_USE → RETURN_PENDING
returns 200 with the updated loan in ApiEnvelope.data. A duplicate
RETURN_PENDING request returns 200 idempotently with no new outbox event.
Wrong guest → 404; other invalid transitions → 409; staff/anonymous
denied. This status remains an active occupied copy. Waiter
`POST /api/v1/waiter/game-loans/{id}/return` now accepts physical
IN_USE or RETURN_PENDING only with a matching copy code and inspection
confirmation. Guest-only request is never final return/payment truth.

## Physical game damaged-copy quarantine (stacked draft PR #22)

Waiter/cashier/manager/admin:
`POST /api/v1/waiter/game-loans/{id}/return-damaged`
with `{"copyCode":"UNO-001","reason":"Two cards are missing"}`.
The confirmed copy is returned and marked DAMAGED atomically, with a durable
condition event and audit. Wrong code or incorrect loan state → 409;
invalid reason → 400. This is not an incident fee or claim of liability.

Manager/admin only:
`GET /api/v1/admin/games/copies` lists at most 250 registered serialized
copies with ID, game ID/name, code and condition (no borrower data).
`POST /api/v1/admin/games/copies/{id}/condition` with
`{"condition":"DAMAGED"|"AVAILABLE","reason":"Inspection details"}`
quarantines idle AVAILABLE or restores inspected DAMAGED only. Assigned
copies, repeat/other transitions → 409; invalid note → 400;
wrong role → 403; missing copy → 404. Mutation responses are 200 in
ApiEnvelope; list responses are no-store. No LOST/RETIRED transition
or automatic damage penalty is exposed.

## Manager-only game condition history (stacked draft PR #23)

`GET /api/v1/admin/games/condition-history` returns up to 100 newest
condition transitions with `id`, `copyId`, `gameName`, `copyCode`,
`beforeCondition`, `afterCondition`, `reason`, `actorLogin`,
`recordedAt` in a no-store 200 ApiEnvelope.data array. 401 unauthenticated,
403 waiter/cashier/kitchen, with no guest or payment identifiers exposed.
Rows are immutable evidence of staff inspection events, not monetary claims.

## M4 game inventory change-only realtime (stacked draft PR #24)

The existing `topic=games` live cursor now contains two kinds of
durable, monotonically ordered markers under one sequence: loan events
remain scoped to their owning guest or responsible waiter; inventory
changes (`GAME_INVENTORY_CHANGED`) are observable by every authenticated,
active-visit game viewer and authorized waiter/supervisor. No guest,
staff or copy identities, inspection notes, quantities, reasons or
payment information are emitted in SSE. `GAME_INVENTORY` outbox records
contain the game UUID and empty JSON; backend checks the referenced
catalogue game before relay publication. This adds no anonymous public
game SSE, no change to financial authority, and no second EventSource.

## M4 paginated manager game-copy inventory (stacked draft PR #25)

`GET /api/v1/admin/games/copies/search?q=<string>&condition=<state>&page=<int>`
is authorized for MANAGER/ADMIN only; 401 anonymous, 403 other roles.
`q` defaults to empty, trimmed at the backend, max 80 characters,
and matches case-insensitive literal substrings of game name or copy
code (SQL %/_ treated literally). `condition` may be blank, AVAILABLE,
DAMAGED, LOST or RETIRED; `page` defaults 0 and must be an integer
between 0 and 2000 inclusive. Invalid parameters → 400; no PII
and no write side effects. Success 200/no-store with
`ApiEnvelope.data = {items:[CopyView],page:int,hasNext:boolean}`
(25 per page, 26 fetched). Order is stable by copy code then ID.
This extends, rather than breaks, the legacy bounded 250-copy list
endpoint while its consumers migrate.

## M4 manager condition-history search (stacked draft PR #26)

Manager/admin bearer only:
`GET /api/v1/admin/games/condition-history/search?q=&change=&page=0`.
Search `q` (0..80 printable chars) is a case-insensitive **literal
substring** over game name, serialized copy code, staff login and
inspection reason; SQL `%`/`_` characters are not wildcards.
`change` can be blank, `DAMAGED` or `AVAILABLE` (the resulting
condition). `page` is zero-based, 0..2000 inclusive; 400 for invalid
filters/page, 401 for missing auth, 403 outside manager/admin. Success
is HTTP 200/no-store `ApiEnvelope.data = {items, page, hasNext}`,
where `items` contains at most 25 existing `ConditionHistoryView`
records. Sort is descending timestamp, then event UUID. No guest,
payment, receipt or join token is returned. This is read-only.
The original `GET /admin/games/condition-history` latest-100 endpoint
remains available during migration.

## M4 missing physical game report and manager review (draft PR #27)

`POST /api/v1/waiter/game-loans/{id}/report-loss` with
`{"reason":"Game not found after physical check"}` accepts only an
active ASSIGNED/IN_USE/RETURN_PENDING loan, with opening-waiter or
supervisor authorization. Returns 200 with incident summary, but
does **not** release inventory or change the loan status.
Duplicate OPEN report or wrong state → 409; wrong waiter → 403.

`GET /api/v1/admin/games/loss-incidents` (no-store) returns up to
100 OPEN-first incident summaries containing incident ID, loan ID,
game, unit code, table code, operational note, review note, status and
timestamp. Only manager/admin (401/403 denial), never guest identity,
phone or payment details.

`POST /api/v1/admin/games/loss-incidents/{id}/review` with
`{"decision":"DISMISS","reason":"Unit physically found"}` or
`{"decision":"CONFIRM_LOST","copyCode":"UNO-001",
"reason":"Serial inventory checked, missing"}`, manager/admin only.
Review is single-use; incorrect serial or stale loan → 409.
A confirmed loss sets both loan and copy LOST transactionally and creates
safe realtime markers; a dismissed claim leaves the active physical loan
unchanged so staff can complete regular inspected return. Incidents are
historical evidence; no fee, liability or automated billing outcome.


## M4 found LOST unit quarantine endpoint (stacked draft PR #28)

`POST /api/v1/admin/games/copies/{id}/recover`
(manager/admin bearer only) expects JSON
`{"copyCode":"UNO-001","reason":"Found unit in backroom; physical inspection needed"}`.
A matching physical serial, verified existing LOST loan and manager-
confirmed loss incident are required; only LOST → DAMAGED quarantine
is possible. Inactive/stale/wrong copy, missing incident or repeated
recovery → 409; invalid note → 400; unauthorized requester → 401/403.
Returns 200/no-store `ApiEnvelope.data = CopyView` with condition
DAMAGED. Explicitly separate `POST /api/v1/admin/games/copies/{id}/condition`
(DAMAGED → AVAILABLE, mandatory new inspection reason) is needed for
restocking. Past LOAN LOST and incident CONFIRMED_LOST remain unchanged.
A safe `GAME_INVENTORY_CHANGED` marker (empty outbox payload) prompts
authorized live catalogue refresh; no guest/financial data is emitted.


## M4 manager searchable loss incident register (stacked draft PR #29)

`GET /api/v1/admin/games/loss-incidents/search?q=&status=&page=0`
is MANAGER/ADMIN only; 401 without staff auth, 403 for guest, waiter,
cashier and kitchen. Search `q` is trimmed to at most 80 printable
characters and case-insensitive literal substring text in name, serial,
table, reporter login, report or decision note; wildcard-looking SQL
characters remain ordinary text. `status` is blank, OPEN, DISMISSED or
CONFIRMED_LOST. `page` is integer 0..2000; invalid filters/page →
400. Success 200/no-store `ApiEnvelope.data = {
  items: IncidentView[], page: number, hasNext: boolean, openCount: number
}`. At most 25 items, OPEN first then newest by reported timestamp and
UUID. `openCount` counts **all** OPEN incidents irrespective of the
query, filter or current page. The legacy `GET /admin/games/loss-incidents`
remains bounded/latest-100 for compatibility. No mutations, private
guest/phone data, or financial fields.


## M4 manager-only OPEN loss-alert summary (stacked draft PR #30)

`GET /api/v1/admin/games/loss-incidents/summary` is manager/admin
bearer-only, HTTP 200, no-store:
`ApiEnvelope.data = {openCount, recent}`.
`openCount` is a nonnegative count of ALL OPEN loss incidents.
`recent` is an ordered array of at most 3 OPEN previews:
`{gameName,copyCode,tableCode,reportedAt}`, newest first using
`reported_at DESC,id DESC`. No incident text, borrower identity,
guest ID, loan ID, phone, receipt, payment or token field is included.
401 no valid staff authentication, 403 wrong authenticated staff role.
No write action or notification delivery endpoint.


## M4 manager incident evidence (stacked draft PR #31)

`GET /api/v1/admin/games/loss-incidents/{id}/evidence`:
MANAGER/ADMIN only, no-store 200 envelope.
`data = { incident: IncidentView, reporterLogin: string,
  reviewerLogin: string|null, reviewedAt: Instant|null,
  activity: IncidentActivity[] }`.
The activity array is bounded to 25 chronological entries with
`{id,operation,actorLogin,occurredAt,correlationId}`. Allowed
operations only: GAME_LOSS_REPORTED, GAME_LOSS_DISMISSED,
GAME_LOSS_CONFIRMED. Report/review notes remain manager-only within
incident; no guest ID, contact, payment or unrestricted audit diff.
401 unauthenticated/guest bearer, 403 waiter/cashier/kitchen,
404 missing incident, 400 invalid UUID. Read-only, no side effects.


## M5 managed staff status (draft PR #40)

- GET /api/v1/admin/staff?page=0: MANAGER/ADMIN, returns {page,hasNext,items[{id,login,enabled,roles,createdAt}]} with no-store; limit 25, page cap 2000.
- POST /api/v1/admin/staff/{id}/status: MANAGER/ADMIN, JSON {enabled,expectedEnabled,reason} where reason is 5-300 printable chars. Only non-manager/non-admin targets, never self. Transactions lock target row, enforce expected enabled state, revoke all live sessions on disable and write immutable staff audit. Replaying a state-change returns 409; disabled sessions remain revoked when later enabled.
- Role assignment, account provisioning and privileged recovery are intentionally out of scope pending a separate security policy.

## M5: admin-provisioned ordinary staff (draft PR #41)

`POST /api/v1/admin/staff` accepts `{login,password,role,reason}` **only with an existing ADMIN role**. Login is 3–40 ASCII letters, digits, dot, dash or underscore (must start with a letter) and is normalized to lowercase. The initial password must be 16–72 printable ASCII characters to avoid BCrypt silent truncation. It is hashed server-side and never returned. Only WAITER, KITCHEN and CASHIER may be created; manager/admin creation and privilege assignment are not implemented. An explicit 5–300 printable-character reason is mandatory. New accounts default `enabled=false` and cannot authenticate until separately enabled via the audited staff-status endpoint. Successful response: 201 with `{id,login,role,enabled:false}`, no-store. Duplicate canonical login: 409, malformed input/role: 400, non-admin: 403. Password rotation, recovery, secure credential distribution and privileged-account enrollment are not implemented.

## M5: self-service staff password rotation (stacked draft PR #42)

POST `/api/v1/staff/auth/password/change` requires a currently valid staff
Bearer token (WAITER, KITCHEN, CASHIER, MANAGER or ADMIN) and JSON
`{currentPassword:string,newPassword:string}`. New passwords must be 16–72
printable ASCII characters without whitespace, different from the existing
password. The server checks the current password under a staff row lock,
rate-limits wrong current-password attempts at five per 15 minutes using
persistent V23 counters, then atomically updates the BCrypt hash, revokes
**every** staff session and appends `STAFF_PASSWORD_CHANGED` audit evidence
without passwords or hashes. Success: 200 no-store
`ApiEnvelope.data="PASSWORD_CHANGED_SESSIONS_REVOKED"`. Missing/expired/
revoked auth: 401; wrong current: 403; invalid input: 400; reused: 409;
rate limit: 429. On success, the Next.js server action at `/staff/account`
clears the path-scoped HttpOnly cookie and redirects to login. This does not
implement privileged password reset, first-login enforcement or MFA.

## M3 sandbox adapter foundation (draft PR #44)

`PaymentGateway` is a Java integration boundary for QRIS/VA: typed create request, deterministic sandbox attempt response, and provider status lookup. `SandboxFakePaymentGateway` is **not registered as a Spring Bean, has no HTTP API, holds no real funds, has no valid payable QR/VA and cannot update an order or intent**. Fake references start with `FAKE_`, and the explicit environment is `SANDBOX_FAKE_NOT_MONEY`. This is only a test foundation, **not** payment acceptance or a verified webhook.

Flyway V24 creates `digital_payment_attempts`, storing foreign-key-linked payment intent, method QRIS/VA, provider key/reference, scoped idempotency key, exact integer IDR amount/currency, state and timestamps. DB constraints reject cash intents, mismatched method/amount, duplicate active attempts, reused payment idempotency keys, mutated financial identity, or terminal-state regression. No application path writes this table until next M3 increment, with a reviewed integration and verified callback/reconciliation design.

## M3 sandbox-only digital attempt coordinator (draft stacked PR)

SandboxDigitalAttemptService is an internal Java domain service with NO HTTP route and no production Spring bean. It is instantiated with JdbcTemplate, TransactionTemplate, in-process SandboxFakePaymentGateway, and Clock. create(GuestPrincipal, orderId, QRIS/VA, key) checks guest ownership of the order and visit and locks the payment intent. It requires an ACTIVE visit, SUBMITTED order, PENDING intent, matching selected digital method, exact amount due and order total, no money paid and IDR. Same-key replay returns the stored attempt. Distinct keys conflict while any CREATED/PENDING attempt remains unresolved even after nominal expiry until future reconciliation. The stored reference has FAKE_ prefix and only PENDING status. Normal customer checkout remains CASH-only; no new HTTP route, provider network call, receipt issuance or payment settlement in this increment.

## M3 dual-guarded sandbox digital checkout (draft PR #46)

Existing POST /api/v1/orders accepts an **optional** paymentMethod field: CASH (omitted defaults to CASH for backward compatibility), QRIS, or VA. Unknown method -> 400. QRIS/VA require BOTH the explicit scs.payments.sandbox-digital-checkout-enabled=true switch and scs.payments.environment=sandbox; otherwise 403 before price, stock or order writes. No normal runtime configuration enables these controls. With both controls, authoritative cart/direct-lines checkout still validates ownership, visit, variants, stock, total and idempotency under a transaction. It creates an order SUBMITTED and a payment_intent matching QRIS/VA with PENDING and 0 amount_paid_idr; kitchen is NOT admitted and no receipt is created. Same idempotency key cannot be replayed with a different method because a method-scoped request hash is used; legacy CASH hashes are unchanged for existing clients. The legacy persisted scope name CASH_CHECKOUT is retained temporarily for cross-method collision protection. This is an internal/test sandbox route only, with no payable QR or VA, fake attempt creation, callbacks or true settlement. Existing public guest UI still offers only cash.

## M3 guest-facing non-payable sandbox attempts (draft PR #47)

Sandbox-only API (controller exists ONLY when scs.payments.environment=sandbox; requests additionally require scs.payments.sandbox-digital-checkout-enabled=true):
- POST /api/v1/me/orders/{orderId}/sandbox-attempts, authorized GUEST bearer, Idempotency-Key: 8–128 permitted characters, body {method: "QRIS"|"VA"}. Returns 200 no-store with an authoritative attempt snapshot {id,orderId,method,amountIdr,providerReference,state,expiresAt,nonPayableDisplayHint,environment}. Values are fake: reference FAKE_..., display hint FAKE_QRIS_NOT_PAYABLE / FAKE_VA_NOT_PAYABLE, environment SANDBOX_FAKE_NOT_MONEY. A same-key replay returns the persisted attempt; different key while unresolved: 409; wrong method or cash order: 409; malformed input: 400; foreign order: 404; anonymous: 401.
- GET same path lists up to 20 of the guest's persisted FAKE attempts, newest first, no-store. Unknown/foreign order: 404; cash order: 409. Neither endpoint includes idempotency key or secrets.
- GET /api/v1/me/orders/{id} now includes paymentMethod CASH/QRIS/VA along with separately authoritative status and paymentStatus. The web app validates this response before rendering.
The Next.js server routes offer sandbox checkout forms and an attempt-creation form ONLY when both SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED=true and SCS_PAYMENT_ENVIRONMENT=sandbox are present. These are display checks; the backend independently verifies authorization and configuration. NO payable QR, bank account, webhook, PAID transition, receipt or kitchen ticket is generated.

## M3 immutable sandbox payment-event quarantine (draft PR #49)

Flyway V25 introduces `sandbox_payment_event_inbox` for **internal synthetic event observations only**. `SandboxPaymentEventInbox` is manually constructed by tests and is **neither a Spring Bean nor an HTTP webhook endpoint**. Input includes synthetic provider event ID, `FAKE_` reference, QRIS/VA method, PENDING/SUCCEEDED/FAILED/EXPIRED claim, amount IDR, claimed currency and environment. It validates bounded IDs and canonical payload data, computes a SHA-256 **fingerprint for duplicate comparison, NOT a signature**, and stores a minimal immutable metadata record. Unique (SANDBOX_FAKE, event ID) deduplicates exact event replays; reuse of the same event ID with altered fields fails 409.

Classifications: QUARANTINED, UNKNOWN_REFERENCE, FINANCIAL_MISMATCH, ENVIRONMENT_MISMATCH, LATE_SUCCESS, CONTRADICTORY_STATE, ALREADY_SETTLED. Even a matching SUCCEEDED observation stays QUARANTINED. No observed event, fake provider reference, hash, classification or timestamp is authoritative financial settlement. The module does NOT update attempts, payment_intents, orders, receipts or kitchen_tickets. No production gateway integration, callback URL, provider signature verification, real provider event ID, or bank/payment transaction is implemented. A future verified provider adapter must remain a separate, reviewed trust boundary.

## M3 admin read-only sandbox reconciliation review (draft PR #50)

GET /api/v1/admin/payments/sandbox-events?page=0&classification=ALL: requires authenticated ADMIN staff role, an explicitly sandbox backend environment AND sandbox-digital-checkout-enabled=true. In ordinary deployments the route does not exist. MANAGER, CASHIER and WAITER must not access this endpoint. Parameters: page integer 0–1000; classification from ALL, QUARANTINED, UNKNOWN_REFERENCE, FINANCIAL_MISMATCH, ENVIRONMENT_MISMATCH, LATE_SUCCESS, CONTRADICTORY_STATE, ALREADY_SETTLED. Invalid page/filter: 400. Response: 200 and Cache-Control: no-store, with page, hasNext, classification, a read-only list of at most 20 records and totalEvidence/successClaims/flaggedEvidence counters. Evidence includes synthetic FAKE provider reference, immutable observed classification, provider event identifier, claim status/method/amount/currency/environment, receivedAt, nullable attemptId/currentAttemptState/currentPaymentState. No raw webhook, signature, credential, customer PII, fingerprint or idempotency key is returned.

The admin-only /staff/reconciliation view validates the backend response before displaying filters and pagination. The UI is hidden unless BOTH sandbox flags are enabled on the web runtime. The endpoint has **no POST/PATCH/DELETE** operation and no action for approving a claim, marking PAID, issuing a receipt or settling funds.

## M3 HMAC-signed FAKE callback quarantine (draft PR #51)

POST /api/v1/integrations/payments/sandbox/events accepts **only synthetic webhook events**, is not registered unless scs.payments.environment=sandbox, and requires an independent sandbox-digital-checkout-enabled=true flag. Authentication does not use a staff token: the endpoint checks HMAC-SHA256 over the ASCII timestamp + "." + **exact raw JSON bytes** before parsing. Headers: X-Sandbox-Timestamp (Unix epoch seconds) and X-Sandbox-Signature (`sha256=` followed by 64 lowercase hexadecimal characters). The signer and server share a local **non-production test-only** secret from `SCS_SANDBOX_WEBHOOK_SECRET` (at least 32 UTF-8 bytes). Missing or weak key: 503; missing/bad signature or timestamp outside ±300 seconds: 401; empty or above 4096 bytes: 400; invalid/extra JSON fields or unsupported event state: 400. Signature does not authenticate any actual payment provider.

Body: `{eventId,providerReference,method,state,reportedAmountIdr,currency,environment}`, with FAKE reference and method QRIS/VA. States PENDING, SUCCEEDED, FAILED and EXPIRED are accepted as **claims only**. Authenticated payloads go to the immutable V25 synthetic event quarantine and receive 202/no-store `{evidenceId,eventId,classification,verdict:"QUARANTINED_NOT_PAYMENT"}`. Exact event identity+payload replay returns the same evidence. A conflicting event ID or payload is rejected 409. No callback route for GET/PATCH/DELETE, no raw payload/fingerprint or secret returned. No order/intent, attempt state, paid amount, receipt, ticket, or money movement is changed. This is NOT a real gateway webhook contract.
