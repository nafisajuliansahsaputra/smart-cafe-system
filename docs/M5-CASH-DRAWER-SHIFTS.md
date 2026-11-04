# M5 Cash Drawer Shift Audit (draft PR #56)

## Scope and limitations

One **single-cafe / single-till** active cash shift at a time. Authorized
CASHIER/MANAGER/ADMIN may open a shift with a manually counted starting float.
The opening cashier or MANAGER/ADMIN can close it, stating the manually counted
cash total and an audit reason. Waiter and kitchen staff cannot access this
screen/API. A database unique partial index prevents multiple active shifts,
including concurrent API replicas.

**This is preliminary physical-till reconciliation**, not a certified financial
settlement. Expected cash = opening float + sum of recorded cash tenders
(received minus change) for the shift's timestamps. It assumes *all* cash
captured by any waiter enters this one till. There is no transfer/handoff
ledger yet, and tender capture is not serialized against shift closure.
A tender committing around the closing boundary can be excluded from the
frozen snapshot even if money was physically counted. For an actual pilot,
staff must pause cash collection, reconcile in-flight tenders, and explicitly
verify handoffs before closing. Do not present discrepancies as theft or
automatic debt. No payment, order or receipt state is edited by this feature.

## Contracts

- GET `/api/v1/ops/cash-shifts`:
  `{active: Shift | null, recent: Shift[]}`; recent has up to 10 CLOSED.
- POST `/api/v1/ops/cash-shifts`, body `{openingFloatIdr}`, plus stable
  `Idempotency-Key` (8–128 ASCII safe characters). Returns same prior
  shift to the same actor + amount on exact replay. Conflict on another
  active shift or incompatible key reuse.
- POST `/api/v1/ops/cash-shifts/{id}/close`, body
  `{countedCashIdr,reason}`, plus stable idempotency key. Only original
  cashier or manager/admin may close. Exact actor/key/amount/reason replay
  returns prior immutable snapshot; changed retries get 409.
- Amount range 0–1,000,000,000,000 IDR, no negative opening/count.
- Backend owns arithmetic and current timestamps. No-store on responses,
  staff HttpOnly token remains in the server-only BFF.

## Persistence and audit

Flyway V28 adds `cash_drawer_shifts`, unique active shift and open-key
constraints, close-snapshot equality constraints, completed-row mutation
denial, a tender time index, and transaction-scoped PostgreSQL advisory
locking for serialized shift open/close. Both actions emit `audit_logs`
attributions with operator, reason and safe numeric facts, all committed
in the same database transaction. Closed shift history cannot be edited
or deleted through SQL without changing schema protections.

## Verification and release gate

Synthetic PostgreSQL Testcontainers checks only one active till, replay,
role denial, an actual completed CASH tender + finalized receipt, gross
cash, physical-count variance, duplicate closure, manager override,
immutable audit and zero payment/receipt mutation.
The staff Chromium cash lifecycle checks opening, live tender count,
closing and discrepancy display, with waiter/kitchen denial.
Require exact-HEAD CI, financial policy review and actual shift/handoff
procedures before any production use.
