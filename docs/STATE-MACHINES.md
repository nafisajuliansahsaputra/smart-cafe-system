# State Machines — Smart Cafe System

**Design contract:** No runtime code exists yet. All transitions are server-authorized, transactional, audited when privileged, and tested with negative scenarios. Use enums as concepts, not database migration finalized names.

## 1. Table visit

States: ACTIVE -> CLOSING -> CLOSED; ACTIVE -> VOIDED (manager incident only).

- OPEN: waiter/cashier with permission; at most one ACTIVE or CLOSING visit per table (partial unique constraint).
- JOIN: only when ACTIVE and guest has visit-specific join proof; static table alias is never proof.
- CLOSE requested: verify no outstanding unpaid orders, unfulfilled paid orders or unreturned games; then CLOSING -> CLOSED atomically after final checks.
- If guest token arrives after close, reject (SESSION_CLOSED).
- Manager override, if enabled, must record reason and outstanding obligations. Override does not erase debt/loans.
- Visit change/table transfer must never silently reassign financial history.

## 2. Order lifecycle

States:

~~~text
SUBMITTED
   | accepted by staff / payment-eligible policy
   v
ACCEPTED -> PREPARING -> READY -> SERVED
   |
   +-> CANCELLED (only when allowed by preparation/payment policy)
SUBMITTED -> REJECTED (authorization/order validity/staff rejection)
~~~

SUBMITTED is persisted order awaiting eligibility; no KDS ticket from this state.

Digital order:
- SUBMITTED + payment PENDING.
- VERIFIED PAID => backend auto-accepts by default if menu remains fulfillable OR sends to exception/manual approval if invalid/out of stock.
- Never silently refund or fail a paid order; OCC must triage and resolve.

Cash order:
- SUBMITTED + AWAITING_CASH.
- Authorized waiter/cashier approves actual table/request -> ACCEPTED while AWAITING_CASH.
- Staff may prepare/serve before money collection; unpaid remains obvious in OCC.
- SERVED does not imply PAID. If no cash received, open payment obligation and alert cashier.

Fulfillment transitions:
- ACCEPTED -> PREPARING: kitchen only.
- PREPARING -> READY: kitchen only.
- READY -> SERVED: waiter only after actual handover.
- Cancellation after PREPARING requires manager policy/incident and no erasure of consumed stock.

Each transition validates current state under DB lock/version check; stale writes get CONFLICT.

## 3. Payment aggregate vs attempt

Aggregate payment states: UNPAID, AWAITING_CASH, PENDING, PAID, PARTIALLY_REFUNDED, REFUNDED, DISPUTED.

Attempt states: CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED, CANCELLED.

Never interpret attempt failure as paid aggregate failure when another attempt already succeeded. Two attempts must not both settle silently; reconcile excess/double charge via cashier/manager exception workflow.

Digital:
~~~text
payment intent -> attempt CREATED/PENDING
  -> provider authenticated + matching amount/order/status
  -> attempt SUCCEEDED -> aggregate PAID
  -> receipt finalization + order admission
~~~

Failure/expiry may allow another attempt with a new provider reference. Late success after expiry is a financial exception to reconcile, not to discard. Status API reconciliation is required if webhook is missing. Browser return is a navigation hint only.

Cash:
- AWAITING_CASH created with order.
- APPROVE_ORDER action does not mutate payment to PAID.
- COLLECT_CASH with staff identity and tender amount -> record cash event and aggregate PAID atomically after validating expected due.
- Miscount/correction cannot overwrite event history; use correction/void/refund with manager access and audit.

Receipt:
- Only generate/issue once aggregate enters PAID and final financial snapshot is known.
- Corrections create explicit adjustment record and a new corrected document reference, not in-place edits.

## 4. Game request, reservation and loan

Game definition != physical copy. Example: UNO game has UNO-001 and UNO-002 copies.

Request lifecycle: REQUESTED -> ASSIGNED -> DELIVERY_PENDING -> IN_USE -> RETURN_PENDING -> RETURNED.

- REQUESTED is a **desire**, not a guarantee that stock has been reserved.
- A game can be allocated only to a valid table visit and per configured eligibility. For free games in MVP, default allocate after staff/order acceptance and availability recheck.
- Reservation has TTL and copy allocation. Expired reservation returns AVAILABLE, unless already handed over.
- ASSIGNED -> DELIVERY_PENDING records waiter task.
- DELIVERY_PENDING -> IN_USE requires waiter confirmation of copy code and physical handover.
- IN_USE -> RETURN_PENDING may be requested by guest or waiter.
- RETURN_PENDING -> RETURNED requires staff confirmation and condition check.
- COPY becomes AVAILABLE again only when returned and serviceable. Otherwise MAINTENANCE, DAMAGED, LOST.
- A copy may have only one active reservation/loan, guaranteed by DB uniqueness/locking.
- If requested game unavailable after digital checkout, do not falsely mark loan delivered. For paid games (future) define compensation/refund before enabling paid rental.

## 5. Receipt delivery

Receipt states: ISSUED; SUPERSEDED by explicit correction only.

Delivery attempt states: QUEUED -> SENDING -> SENT or RETRYABLE_FAILURE -> QUEUED; terminal FAILED / CANCELLED (no consent).

- A finalized receipt exists independently of messaging success.
- Dispatch only when a current valid consent/channel is stored and active.
- Deduplicate by receipt ID + channel + delivery intent, with bounded retries and provider idempotency where supported.
- Provider acknowledgement does not guarantee customer reading; use SENT vs DELIVERED only if confirmed by provider.
- Expiring receipt links should not disclose personal phone/order details to unauthorized parties.
- Customer can access own receipt in session even if phone delivery fails.

## 6. Example invariants to test

1. SUBMITTED cash, unpaid -> APPROVE -> ACCEPTED, still AWAITING_CASH.
2. Kitchen cannot prepare SUBMITTED unpaid digital order.
3. Customer cannot mark payment PAID or order READY.
4. Duplicate digital success event creates no duplicate ticket or receipt.
5. Failed attempt arriving after paid does not regress aggregate state.
6. Only one active loan per game copy under concurrent load.
7. Table cannot close with a borrowed game or pending cash.
8. Customer with an expired visit token cannot reuse old QR to order.
9. Resending a receipt does not produce duplicate charge or new order.
10. Late digital payment success on cancelled order goes to reconciliation/exception queue; never silently fulfill or discard.

## 7. M2 implementation guardrails

Cash may be approved for cooking and even served while still AWAITING_CASH. In the current development-only implementation, recording the cash tender requires the order to have reached SERVED. After confirmed staff tender, cash payment and immutable receipt are committed together. V5 independently prevents receipts for unpaid intents. A waiter must have opened the visit to manage its visit/serving/cash until a proper assignment system is available. These temporary merchant policies are not approved for production use.

## 8. Cash order stock release (M2 implementation)

- Cart is editable until checkout and holds no stock. Cart updates use a version check; checkout revalidates server price and decrements item-limited stock atomically.
- After cash checkout, SUBMITTED + AWAITING_CASH has tracked reserved menu units; cashier may ACCEPT, or REJECT before cooking. REJECT returns still-outstanding allocations exactly once, keeps the order immutable, and moves the uncollected intent to UNPAID.
- A REJECTED unpaid order does not block closing a visit; a served but unpaid order still blocks closure. Rejection after acceptance, preparation or payment is rejected until a separately reviewed cancellation/refund policy is implemented.
