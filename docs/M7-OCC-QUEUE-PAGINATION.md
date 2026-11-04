# M7 Order Control Center: PostgreSQL paginated queue (stacked draft)

## Problem
The historical cashier OCC `GET /api/v1/ops/orders` returns only the
latest 100 rows. Without pagination, the operational screen silently loses
older orders, especially unpaid cash and exceptions, when a cafe is busy.
Filtering the capped results client-side cannot recover those records.

## Solution
- New **cashier/manager/admin-only** `GET /api/v1/ops/orders/page` with
  `page` (0-based, 0–1000), optional `status`, `paymentStatus`,
  `tableCode`, and optional `orderId`.
- 25 rows per page; returns `{page,pageSize,total,hasNext,items}`.
  PostgreSQL performs both the count and bounded page under a repeatable-read
  snapshot. Ordering is deterministic by `created_at DESC, id DESC`.
- Shared validation for status, payment status and exact physical table code;
  input is parameterized, never interpolated into SQL. Invalid page and filter
  cause HTTP 400. Response requests `Cache-Control: no-store`.
- Legacy `GET /api/v1/ops/orders` remains unchanged for old clients and
  current internal consumers.
- `/staff/orders` now reads a server-only staff bearer-scoped page. The web
  frontend rejects malformed responses instead of displaying an empty queue;
  displays total count, Previous/Next, filters and refresh preserving page.
  The existing order detail and financial commands are unchanged.

## Verification
- PostgreSQL integration builds **108 real synthetic orders**, asserts all
  five pages and old legacy cap, stable unique UUIDs, no-store, status/payment/
  table search, invalid input, and authorized/unauthorized role matrix.
- Existing Playwright cash/receipt journey now checks real cashier search,
  invalid page and 320px responsive rendering, while preserving the complete
  guest→OCC→KDS→waiter→cash→receipt flow and screenshot evidence.
- CI exact-head Java/Testcontainers, Next.js lint/types/build, browser E2E,
  security workflows and HTTPS edge must pass before calling this done.

## Limitations and release gates
- Pages use offset, so a new incoming live order can shift rows between
  separately requested pages. Each page is individually consistent. The
  live SSE refresh still reloads canonical state. **Do not interpret this
  as a frozen historical snapshot across navigation**.
- Page index is bounded to 1000. This is an operational inbox, not an
  exhaustive multi-year archive; broader historical reporting lives
  separately. Investigate cursor pagination if measured growth warrants it.
- Requires independent release review, protected branch/owner approval,
  isolated staging and device acceptance. Does not enable real merchant
  payments, production hosting or external receipt delivery.
