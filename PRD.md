# Smart Cafe System — Product Requirements Document

**Status:** Approved planning baseline v1.0; implementation not started  
**Product:** Smart Cafe System (SCS)  
**Repository:** nafisajuliansahsaputra/smart-cafe-system  
**Audience:** Product owner, coding agents, designers, reviewers, future cafe operators  
**Date:** 2026-10-29

## 1. Product definition

Smart Cafe System is a web-first, QR/NFC-powered dine-in order and cafe operations platform. It coordinates customer orders, an authoritative **Order Control Center (OCC)** operated by cashier/admin, waiter delivery and cash handling, kitchen preparation, physical-game loans, payment processing, and optional digital receipt delivery to the customer's phone.

The MVP serves **one cafe with many tables**. It is portfolio-grade enterprise software, not a claim of production certification.

## 2. Goals

G1. Let guests order from a table through a mobile browser without installing an app or registering an account.

G2. Provide a mandatory protected **Order Control Center** to supervise all orders, payment states, kitchen routing, exceptions, and staff actions.

G3. Support QRIS and virtual-account checkout through a sandbox payment gateway, with verified asynchronous payment confirmation; support staff-approved cash due upon delivery.

G4. Allow customers to request board/card games as **physical inventory loans** delivered/collected by waiter.

G5. Make phone number useful for **optional delivery of a finalized receipt**, with a separate channel opt-in and fallback to viewing/downloading the receipt in the web session.

G6. Demonstrate Java/Spring Boot, SQL transactions, API security, real-time UI, automated testing, CI/CD, and observability.

## 3. Non-goals for MVP

- No customer account or loyalty account required.
- No delivery/takeaway, multi-branch or multi-tenant billing.
- No digital online games, gameplay integration, or copyrighted game assets.
- No split bill or partial payment, unless a later ADR explicitly adds them.
- No mandatory WhatsApp provider integration on the first cash-order MVP.
- No claiming financial certification, tax compliance, or end-to-end production readiness before validation.
- No Kubernetes, service mesh, or microservices solely for portfolio theater.
- No requirement that every menu item track ingredient-level stock in the first slice.

## 4. Actors and permissions

- **Guest:** table-scoped ephemeral session; own cart/orders and own receipts only.
- **Waiter:** start/close table visits, cash-order approval, delivery and game handover/return, confirm physical cash collection when permitted.
- **Kitchen staff:** see eligible food tickets; prepare/ready; no access to phone details or payment credentials.
- **Cashier:** use OCC; view payment states, collect/reconcile cash, supervise orders, resolve allowed exceptions; restricted refund/void permission.
- **Manager/Admin:** manage catalog, tables, game inventory, staff and sensitive overrides; view audit and reports.
- **System worker:** dispatch receipt/outbox events and reconcile provider status under service identity, not a human role.

OCC is explicitly available to cashier and manager/admin; waiter gets an operational task view, while kitchen gets KDS. Actions are authorized server-side per capability.

## 5. Core journeys

### J1: QR/NFC visit and guest identity
1. Waiter starts a table visit and provides a short-lived join mechanism.
2. Static QR/NFC resolves a non-secret table alias and active visit context.
3. Guest enters nickname and can supply phone number for receipt delivery. Valid Indonesian phone numbers normalized to E.164 if supplied.
4. Guest joins active visit with scoped, revocable server session; rejected when closed/expired or join proof absent.
5. Another guest can join the same visit; carts are isolated per guest.

Acceptance: no login required; a copied QR alone cannot authorize remote checkout; ended table credentials cannot be reused.

### J2: Food/drink order
1. Browse available categories and items with descriptions, prices, variants, allergens/notes when configured.
2. Change quantities and customizations; see calculated subtotal.
3. Server rechecks price, availability, table status, ownership and item constraints at checkout.
4. Submit using an idempotency key; order receives immutable item price snapshots.
5. Guest tracks lifecycle; waiter/KDS/OCC receive eligible live updates.
6. Guests may create **additional orders** during the same visit, each with its own payment.

Acceptance: server totals authoritative; replays do not create duplicates; unavailable item returns actionable correction.

### J3: Digital payment
1. Guest selects QRIS or VA, backend creates one tracked payment attempt via payment adapter.
2. Pending payment does not release food tickets to kitchen.
3. Provider notification is authenticated, validated against amount/order identity, persisted and deduplicated.
4. Verified success makes order paid/eligible for staff acceptance and fulfillment.
5. Timeout, duplicate callbacks, out-of-order events, canceled/expired payment attempts and reconciliation are tested.
6. Receipt generated once payment and amount are final.

Acceptance: browser redirects alone cannot mark payment successful; no duplicate fulfillment on duplicated webhook.

### J4: Cash on delivery
1. Guest chooses cash; order becomes SUBMITTED with payment state AWAITING_CASH.
2. OCC shows order as **approval required**. Authorized waiter/cashier verifies visit/order and approves.
3. Approved unpaid cash order may enter KDS; kitchen preparation is separate from payment.
4. Waiter delivers food and collects the actual cash; authorized staff records receipt, change/tender, and transaction evidence.
5. Only then is payment PAID; any discrepancy is flagged for cashier/manager resolution.
6. If waiter cannot confirm validity, decline/cancel before preparation.

Acceptance: approval != payment; no paid state without recorded cash receipt; OCC always displays unresolved cash.

### J5: Physical game borrowing
1. Guest selects a physical game during checkout or an additional visit request.
2. Backend checks per-copy availability; reservation/assignment is transactional and bounded.
3. Staff accept and deliver the allocated copy, recording staff/copy/time.
4. Guest returns game; staff inspect and mark returned; unavailable/damaged/lost units remain accounted.
5. A visit cannot close normally while a live game loan remains; manager override requires audit.

Acceptance: same game copy never assigned to two active loans; game delivery and food delivery are independent.

### J6: Order Control Center (mandatory)
OCC is the single operational cockpit for cashier and manager/admin:
- all live orders grouped by table and time, filters for status/payment/method;
- search by receipt/order ID, table and time (phone queries limited by permission);
- cash approval queue, payment pending/failed, KDS readiness, serving/delivery queue;
- itemized order detail with server-authoritative totals, paid/unpaid difference;
- allowed actions: approve/reject cash, triage, reassign waiter, cancel pre-preparation, record cash, refund/void through protected flow, resolve failed game allocations;
- audit trail of actor/reason/timestamp; live updates with snapshot/refetch recovery;
- shift-level summary and reconciliation (full analytics later).

Acceptance: OCC is not a mock/admin-only list; authorized cashier can operationally handle orders without editing database.

### J7: Digital receipt by phone
1. Finalized receipt is always visible and downloadable in guest session.
2. Optional phone number is collected for purpose of sending this receipt; channel and opt-in are explicit. No marketing opt-in is bundled.
3. After payment reaches confirmed PAID, generate immutable receipt and queue a delivery request via outbox.
4. Once an approved WhatsApp/SMS provider is configured, worker sends a brief receipt link or allowed template; failed delivery retries safely.
5. User may access receipt without sending, and cash receipt generates only **after actual cash collection**.

Acceptance: no receipt before payment; no silent marketing; no phone numbers in public URLs or logs; failure to send does not undo paid order.

### J8: Close visit
Waiter checks all accepted orders, outstanding payments and active game loans. Table closes only after all liabilities are settled or an authorized documented exception is recorded. The next visit gets new identity and tokens.

## 6. Mandatory screens

Customer: table entry/join, name+optional phone+receipt consent, menu, item detail, cart, game catalog, checkout review, payment method/instructions, order tracking, receipt.

Staff: login, **Order Control Center (cashier/manager)**, order detail/exception resolution, waiter table board/tasks, kitchen display, game handover/return, cash tender collection, admin catalog/tables/games/users/settings, basic audit.

Each screen needs loading/empty/error/expired-session/access-denied recovery states, touch ergonomics, and accessibility.

## 7. Functional invariants

- QR table identity != payment QRIS.
- Table alias is not an authentication secret; join credential rotates per visit.
- One active visit per table enforced by DB constraint.
- Multiple guest sessions may exist per visit but are separately scoped.
- Persist immutable item/fee/tax snapshots with each submitted order.
- Payment attempt and aggregate payment/order state are distinct.
- Cash approved to cook can be unpaid; food served does not imply paid.
- Kitchen never controls payment; customer never controls staff order state.
- Physical game stock uses individual copy IDs and collision-safe allocation.
- No phone required to order; receipt channel is optional.
- Never accept a provider success based on a frontend URL, screenshot, or unverified callback.

## 8. Quality and operating requirements

- Server returns explicit validated errors; no silent successful fallback.
- Responsive on normal mobile browsers; accessible labels, keyboard navigation and visible focus on staff UI.
- SSE reconnects with full server snapshot to avoid stale staff queues.
- Authenticated staff actions and privileged financial actions audited.
- Payment, inventory and state transitions covered by negative, concurrency and integration tests.
- Baseline local startup via Docker Compose; no mandatory paid cloud provider to develop.
- Backup/restore, staging verification, health checks and secret handling before a production pilot.

SLOs, load targets, transaction volume, operational SLA, retention periods, tax rules and live provider activation remain **decisions to measure/validate**, not invented promises.

## 9. Scope stages

- **M0:** docs, ADR, API/data contracts and repo scaffold.
- **M1:** tables, guests, menu, cart, cash-order approval, OCC, waiter/KDS happy path.
- **M2:** real-time reliability, audit, test coverage, basic catalog/game model.
- **M3:** Midtrans sandbox QRIS/VA, payment attempts, reconciliation, payment receipts.
- **M4:** physical game allocation/delivery/return.
- **M5:** phone receipt delivery adapter and operational reporting.
- **M6:** security, deployment, monitoring, disaster recovery and pilot readiness.

Project done means acceptance evidence exists, not that a checkbox was written.

## 10. Open decisions (do not silently choose)

- Whether cash accepted strictly at delivery or can also be collected at cashier.
- Receipt delivery channel/provider choice (WhatsApp Business API vs SMS), costs/opt-in/template approval.
- Game loan fee/deposit/late/damage policy; MVP default no fee.
- Tax/fee application per cafe and applicable jurisdiction.
- Who authorizes refunds, discounts and canceled paid orders.
- How much of visit/order history may be accessed after session ends.
- Whether split payments and shared bills are needed after MVP.

Track any decision changes in docs/decisions; update this PRD before altering implementation.
