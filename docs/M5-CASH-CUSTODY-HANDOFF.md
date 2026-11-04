# M5 cash custody and close fence (stacked PR #57)

## New accounting rules

Cash tender capture, handoff receipt, and shift opening/closing all take
the **same transaction-scoped PostgreSQL advisory lock** (48725021).
That fence is acquired before a new tender is persisted, and remains held
until the complete cash tender/receipt transaction commits. Shift closure
waits until pending capture transactions commit, then freezes its snapshot.
The shift's expected collection now sums `cash_tenders.cash_shift_id`, not a
clock-time range vulnerable to PostgreSQL transaction-start timestamps.

Each tender captured with an active shift is assigned that shift ID in the
same transaction as payment finalization and receipt issuance. A
WAITER-only tender creates one pending cash custody record for the waiter;
CASHIER/MANAGER/ADMIN captures are direct-to-till and do not need handoff.
Receipt status remains PAID after waiter capture, but *drawer custody* is
PENDING until separately acknowledged. Do not interpret PAID as proof that
a cashier physically holds money.

## Staff workflow

- Cashier sees the count and list of pending waiter cash handoffs at
  `/staff/cash-shifts`, 50 oldest pending per view.
- On physically receiving cash, cashier confirms the *net amount*
  (cash received minus change) for that exact tender.
- POST `/api/v1/ops/cash-shifts/{shiftId}/handoffs/{tenderId}/receive`
  requires authenticated CASHIER/MANAGER/ADMIN, Idempotency-Key, exact
  countedNetIdr, and confirmedPhysicalReceipt=true. Different counts or
  replay parameters are rejected with 409, without an audit mutation.
- Confirmation writes cashier ID, timestamp and a unique key in one
  database transaction and an append-only audit row. A DB trigger
  prevents updating/deleting a received handoff.
- Shift closure rejects with 409 while *any* waiter handoff in that shift
  remains pending. The closing form is also hidden, with a warning
  until all pending handoffs are acknowledged. The backend gate is
  authoritative: bypassing the UI never bypasses the closing check.
- Cash shift open, close and handoff APIs share the existing /ops/**
  role gate. The waiter and kitchen have no access.

## Compatibility and remaining operational risk

Previously finalized cash tenders (before the first-ever shift is
opened) may have `cash_shift_id=NULL` and are excluded from drawer
reconciliation. After at least one shift exists, *new* CASH tender
capture is rejected when no shift is active. Exact-key duplicate
capture replays retain their previous receipt result.

This is still a **single-cafe/single-till** accounting approximation.
It does not model expenses, payouts, bank deposits, refunds, manager
cash adjustments, change-float transfers, offline payments or disputes.
Physical cash identification relies on human confirmation; the server
cannot verify banknotes. Cashier direct capture assumes immediate till
custody. Never use drawer discrepancy to infer theft. Physical handoff
still requires operational training and human oversight.

## Tests and release

- Testcontainers verifies pending custody blocks shift closure, wrong
  net amount fails, waiter cannot acknowledge, cashiers can confirm,
  replay is safe, confirmed handoff is immutable and audit exists.
- Browser cash lifecycle confirms waiter tender turns PAID, cashier
  sees pending money, cannot close, receives physically, and can
  then close with a synthetic discrepancy.
- Existing CI covers API, Flyway, PostgreSQL, Next.js, cash browser,
  fake QRIS/VA sandbox, HTTPS edge, multi-replica, CodeQL and OSV.
- Require green exact-HEAD CI, manual process audit, and explicit release
  authorization. No real provider settlement, main merge, or production.
