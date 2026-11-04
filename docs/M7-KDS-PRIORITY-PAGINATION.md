# M7 Kitchen Display: priority queue and untruncated page

## Risk addressed

The original kitchen dashboard called `GET /api/v1/kitchen/tickets`, ordered
by admitted time and truncated to 100 tickets. With more than 100 jobs,
older/preparing or recently accepted work could become unreachable. READY
acknowledgments also occupied the same limited list as active cooking work.

## Implementation

- `GET /api/v1/kitchen/tickets/page?page=0` is authorized exclusively to
  KITCHEN, MANAGER, and ADMIN under the same Spring Security boundary as
  the legacy endpoint.
- PostgreSQL returns `{page,pageSize,total,hasNext,items}`, 25 tickets per
  page, with stable order **PREPARING**, then **ACCEPTED**, then **READY**.
  Within each lane, earlier `admitted_at` followed by UUID breaks ties.
- Total count and bounded ticket/line data are read in one repeatable-read
  transaction. Page 0–1000 is permitted; invalid values are rejected.
- Response uses `Cache-Control: no-store`. Guest phone, proof, financial
  amount, and auth details are never returned on the kitchen projection.
- The real Next.js `/staff/kitchen` board now uses a server-only bearer-
  scoped page with previous/next controls, visible total count and fail-
  closed explicit error states. SSE continues refreshing canonical data.
- The existing non-paged API remains for backward compatibility. Kitchen
  state mutation rules, staff rights and order/payment separation are
  unchanged. No schema migration or live payment path was modified.

## Acceptance

- PostgreSQL Testcontainers creates 110 READY tickets plus one PREPARING
  and one ACCEPTED. Verifies **112 distinct rows over five pages**,
  priority order, legacy cap, no-store, invalid pages and staff role
  denials. Both tests use one disposable class database and do not depend
  on a global zero-row count.
- The authenticated end-to-end Chromium cash lifecycle now checks the
  real paginated kitchen page and rejects invalid page input before
  confirming an accepted order and moving it through PREPARING and READY.
- Exact-head source and full-stack integration candidate CI must be
  independently successful before any release decision.

## Known limitations

Offset pagination is a consistent snapshot **within one page**, not across
multiple navigation requests: kitchen state changes can move jobs between
pages. Live SSE refresh and server-authoritative transitions remain the
source of truth. A real shift needs tablet accessibility, screen-reader
checks, human operators and measured concurrency. This remains a draft
candidate, not a production deployment. Merchant QRIS/VA and external
receipts are not implemented as live providers.
