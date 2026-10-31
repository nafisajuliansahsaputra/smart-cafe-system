# M5: Cash receipt ledger (stacked draft PR #55)

## Purpose

Cashiers, managers and admins can inspect individual immutable CASH receipts for a
selected Asia/Jakarta (WIB) day, alongside the existing daily gross aggregates
and CSV export. It is an operational read-only view, **not** reconciliation of
the till, bank, refunds, net revenue or actual money in a merchant account.

## API contract

`GET /api/v1/ops/reports/cash-daily/receipts?date=YYYY-MM-DD&page=0`

- Existing Spring Security /ops/** gate requires CASHIER/MANAGER/ADMIN. No
  guest, waiter, kitchen, anonymous or public route is added.
- `date` is required, Gregorian and restricted to years 2000–2100; the
  backend converts WIB midnight boundaries to instants, not server-local days.
- `page` defaults to 0, accepts integers 0–1000. Exactly 20 entries per
  page, 21 rows internally for a bounded `hasNext` flag. Cursor across
  page requests is **not** a frozen snapshot: concurrent new receipts may
  shift older offset pages. Always refresh after a shift.
- Ordering `issued_at DESC, receipt_id DESC` is deterministic. V27 adds
  the corresponding index to support date-window reads.
- Each row has `receiptId`, `receiptNumber`, `orderId`,
  `tableCode`, `totalIdr` and `issuedAt`. No customer identity,
  phone number, raw tender secret or staff access token is included.
- Source is **issued CASH receipts** only, matching the aggregate report's
  gross semantics even if a later refund changes the current payment state.
- Every response uses `Cache-Control: no-store`; there is no mutation route.

## Verification

- Testcontainers verifies 22 paid receipts across two bounded pages and
  adjacent WIB midnight boundary exclusion.
- Existing cash lifecycle integration covers actual issued receipt identity,
  amount, 0-row day, role/anonymous denial, invalid dates and page validation.
- Chromium cashier browser checks the new detail table and that no guest name
  is rendered, while maintaining existing CSV/role denial tests.
- Existing CI must pass exact PR HEAD before review/merge.

## Outstanding

- Real drawer opening/closing counts, discrepancies, cashier shift logs and
  actual cash audits are not implemented.
- No true QRIS/VA merchant payment, refund/chargeback policy, mobile operator
  pilot, production ingress, or customer data release is approved.
