# M7 Waiter task inbox: pending cash first, no 100-order truncation

## Operational risk

The original `/api/v1/waiter/orders/tasks` had `LIMIT 100` in newest-first
order. A busy shift could conceal old served-but-unpaid cash from the waiter
screen. Silently missing unsettled money is an operational hazard.

## New read-only endpoint

`GET /api/v1/waiter/orders/tasks/page?page=0`

- Accepts page 0–1000, responds with `{page,pageSize:25,hasNext,total,items}`.
- Orders tasks by **SERVED+AWAITING_CASH first**, then READY for delivery,
  then recently SERVED+PAID. Within priority groups, oldest first, UUID as
  a deterministic tie breaker.
- Uses PostgreSQL `LIMIT 26 OFFSET page*25`; counts and items share a
  repeatable-read snapshot. Older tasks never disappear just because there
  are over 100 newer records. Legacy `/tasks` remains unchanged.
- Same user scope as the original queue: ordinary WAITER sees only visits
  they themselves opened; cashier/manager/admin may supervise all. Kitchen
  and unauthenticated callers are denied by backend Spring Security.
- No-store cache policy. No join proof, guest token, phone, raw audit trail
  or receipt link is projected.

The Next.js `/staff/waiter` screen uses this server-only authorized data
source, displays count and previous/next navigation, and rejects malformed
backend payloads or out-of-range pages rather than declaring a blank task
list. SSE refresh stays active. Existing serve and cash capture actions,
confirmation checkboxes and idempotency rules are unchanged.

## Verification and limitations

- PostgreSQL 17 Testcontainers seeds 110 READY tickets and one additional
  SERVED-but-AWAITING_CASH order. Verifies 111 accessible tasks across five
  pages, the old 100-row compatibility endpoint, oldest cash collection
  priority, no-store, invalid page and ordinary-staff cross-table denial.
- Existing full cash-to-receipt Playwright browser flow also tests the
  paginated waiter page and invalid page handling, while preserving SSE
  delivery/recorded tender assertions.
- SQL is read-only and bounded. The page uses offset pagination: new state
  changes between separate navigation requests can shift rows. The
  canonical server state is re-fetched after SSE updates.
- Needs exact-commit source CI and candidate-to-main checks; then human
  financial/privacy/security review and real device/staging acceptance.
  Production merchant QRIS/VA, consent-based external receipt sender and
  hosted deployment remain separate unresolved gates. No live money is used.
