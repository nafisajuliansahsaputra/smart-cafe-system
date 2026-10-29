# M4: Physical game loans, initial vertical slice (stacked Draft PR #19)

**Scope:** free physical card/board games loaned by the café, not a web game. Customer receives no new fee, deposit, payment intent, digital game, or automatic damage charge. No public deployment and no real customer data.

## Workflow

A manager/admin registers a physical **game type** and one or more physical **serialized copies** through authenticated /api/v1/admin/games endpoints (UI available to admin/manager at /staff/games). The default condition is AVAILABLE; catalog exposure only includes active game types. The database is deliberately unseeded outside disposable browser acceptance.

A guest must first join an **ACTIVE** table visit with the existing separate, revocable guest session. They open /visit/games to browse games, see approximate free-copy counts and request one by game ID. A new loan starts as REQUESTED; **a request does not reserve a copy**. The guest can see only their own loan records. One visit may not have more than one active loan/request for the same game type; another game/type may be requested. No order/checkout, cash state or receipt is mutated.

The waiter who opened that visit, or an eligible supervisor (cashier/manager/admin), uses /staff/games to:
1. **Assign** a currently AVAILABLE, physically serialized copy from REQUESTED → ASSIGNED. The server locks the unit `FOR UPDATE SKIP LOCKED`, selects only units not already active and records the exact copy ID under a DB partial unique index.
2. **Confirm physical handover** from ASSIGNED → IN_USE. Staff must explicitly type the **actual copy_code**; a mismatching code is rejected, not optimistically fulfilled.
3. **Confirm inspected return** from IN_USE → RETURNED using the same physical code and explicit checkbox. The unit becomes eligible for a new assignment. Because damage/loss policy is not approved, staff must **not** mark a damaged/lost unit as inspected and ready; quarantine/incident-resolution is future work.
4. Alternatively **cancel only a REQUESTED** loan to resolve a request that cannot be fulfilled. Assigned/in-use copies cannot be silently cancelled.

A waiter not responsible for the visit cannot see/manage its loans; kitchen cannot access waiter games; manager/admin alone can add catalog types and serial numbers. All staff mutations write safe audit records in the same DB transaction as the status change.

## Collision / closure integrity

Flyway **V13** creates `physical_games`, `physical_game_copies`, `physical_game_loans`:
- Composite FK on (copy_id,game_id) ensures a loan never refers to a copy of a different game type.
- Partial unique `copy_id` for ASSIGNED/IN_USE prevents **one physical copy being issued to two live visits** at once, including concurrent racing requests.
- Partial unique (visit_id,game_id) for REQUESTED/ASSIGNED/IN_USE prevents duplicate active requests for one type on a table.
- Visit row locks on guest request, waiter transitions and table close ensure closure cannot race with a new pending/active loan.
- Table close refuses **any REQUESTED, ASSIGNED or IN_USE loan**, even when all food cash orders are PAID/SERVED.
- RETURNED and CANCELLED remain recorded; audit events cannot be deleted as a workflow shortcut.

## Endpoints

Guest Bearer-only:
- GET /api/v1/me/games: game title/description and currently free copies; no serial codes.
- GET /api/v1/me/game-loans: only the signed-in guest's loans in current visit; copy code only once IN_USE.
- POST /api/v1/me/game-loans: `{"gameId":"uuid"}` for active visit.

Waiter/cashier/manager/admin Bearer-only:
- GET /api/v1/waiter/games, GET /api/v1/waiter/game-loans (for opening waiter or supervisor).
- POST /api/v1/waiter/game-loans/{id}/assign, /cancel; /handover or /return with `{"copyCode":"UNO-001"}`.

Manager/admin Bearer-only:
- POST /api/v1/admin/games `{"name":"UNO","description":"..."}`.
- GET /api/v1/admin/games and POST /api/v1/admin/games/{id}/copies `{"copyCode":"UNO-001"}`.

No anonymous game catalog API is authorized. Session cookies are sent to the same-origin **Next.js BFF only**, not to Spring Boot; the BFF holds Bearer credentials server-side and validates UUID/form fields. New GET responses are no-store. No PII or join proof is exposed via game endpoints.

## Acceptance and remaining work

PostgreSQL/Testcontainers integration asserts scope isolation, role denial, duplicate requests, unavailable copy, wrong unit code, all transitions, closure conflict, guest revocation after close, and concurrent last-copy assignment. Full Chromium cash cycle additionally requests a synthetic UNO deck, verifies handover, demonstrates **cash PAID is still insufficient to close when a physical unit is out**, confirms return and only then closes the table.

**Not yet implemented:** game event publication / realtime SSE (these two pages have a manual refresh), inventory damage/loss/quarantine/replacement, per-unit maintenance, richer admin editing/deletion, guest return request, stock and courier assignments, reservations/refunds, charges/deposits, true event-driven staff task notifications, physical QR/NFC copy scans. Treat game requests as explicitly provisional pending staff verification. This is the first vertical slice of M4, not full release.

## Game event-driven refresh (stacked draft PR #20)

Every successful loan transition writes a safe, empty-payload GAME_LOAN outbox
event in the **same transaction** as the state mutation. Flyway V14 creates
`live_game_signals` with a unique outbox reference and durable sequence.
The existing PostgreSQL relay/advisory lock and NOTIFY wakeup publish these
without consuming business outbox state. Game event types:
GAME_LOAN_REQUESTED, GAME_LOAN_ASSIGNED, GAME_LOAN_HANDED_OVER,
GAME_LOAN_RETURNED, GAME_LOAN_CANCELLED.

- Signed-in guests can subscribe only to their own game loans in their active
  visit, even when other guests use the same table.
- The responsible waiter sees only loans for visits they opened; cashier,
  manager and admin may subscribe to the whole game task queue. Kitchen and
  anonymous users are denied. No copy serial, game payload, credentials or
  guest identifiers travel in the browser SSE message.
- Both game pages now receive change-only signals and re-fetch authoritative
  scoped data. Existing bounded reconnect/offline recovery applies. This does
  not introduce optimistic handover or offline completion.
- Manager catalog additions and new serial-copy registration are **not**
  separately published as game-loan events; manual refresh still exists for
  catalog-only changes. Full inventory live coverage and real-device QA remain
  future work.

Acceptance: isolated PostgreSQL game event and role/ownership test, existing
HTTP and HTTPS Chromium cash+physical-loan lifecycle extended with live
waiter request and guest handover checks. Not production-ready; retain draft
and review exact-head CI before marking any rollout gate complete.
