# M7: Paginated waiter floor board (draft)

## Why

The original operational `GET /api/v1/waiter/tables` returned only 250
tables. Physical inventory and QR printing can already exceed that limit, but
waiters could not open or close visits for tables beyond the first 250 in the
actual web UI. This change closes that functional gap.

## Implementation

- `GET /api/v1/waiter/tables/page?page=0&q=` is role-protected for
  WAITER/CASHIER/MANAGER/ADMIN. It returns at most 25 rows and a bounded
  `page`, `pageSize`, `hasNext`, `total` envelope.
- PostgreSQL performs the search, stable ordering (`table_code, id`), count,
  pagination, and active-visit join. Query length is at most 60 and permits
  ASCII letters, digits, hyphens and underscores. `POSITION` performs
  literal case-insensitive search, so underscores cannot become wildcards.
  Page indexes are limited to 0..1000. Reads use REPEATABLE READ.
- The API never returns join proofs. A waiter can see their own visit UUID
  and proof expiration; unrelated waiters see occupied status with null visit
  detail. Cashier, manager and admin continue to supervise.
- The existing list endpoint is retained for old clients; the real
  `/staff/tables` page exclusively consumes the new paginated endpoint
  through a server-only bearer-authenticated Next.js API call.
- Search, reset, total count, previous/next and invalid-input recovery
  work at mobile widths. Table open/rotate/close actions remain the existing
  confirmed server actions and preserve their financial/visit gates.
- No table alias or QR/NFC address is changed; no schema migration, payment
  provider credentials, receipt or cash data are modified.

## Verification

PostgreSQL integration tests create 253 synthetic tables, retrieve page 11,
assert owner/unrelated waiter/cashier visit separation, no join proof, no-store,
page/query input rejection, anonymous rejection and kitchen role denial.
Playwright covers manager-created table search, mobile width and invalid page
error through the real backend. Run exact-head Java/PostgreSQL and Chromium
CI before approval.

## Remaining release gates

This is stacked over PR #80, not merged to `main` and not deployed. A
production pilot still requires an isolated HTTPS environment, checked
operator devices, load and accessibility review, independent financial and
security sign-off, approved real merchant provider integration and explicit
owner release authorization. `/showcase` is only a clearly labeled
portfolio simulation, not evidence of actual payment settlement.
