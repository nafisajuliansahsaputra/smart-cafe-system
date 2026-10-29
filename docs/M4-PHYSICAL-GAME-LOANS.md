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

## Guest return pickup request (stacked draft PR #21)

The guest who physically holds the copy can request waiter pickup after
handover. This changes IN_USE → RETURN_PENDING; it **does not** change
copy ownership, mark returned, release stock, or close the table. Only a
responsible waiter/supervisor may perform RETURN_PENDING → RETURNED after
matching the physical unit code and confirming inspection. Existing direct
IN_USE → RETURNED remains allowed for proactive physical handover.

POST `/api/v1/me/game-loans/{id}/request-return` is guest-Bearer only.
It checks the active visit and exact owning guest under visit/loan locks.
Foreign guest IDs return a safe 404. An already-pending duplicate request
returns the same state without a second event. The safe
GAME_LOAN_RETURN_REQUESTED event is stored transactionally and projected
into V15-aware live signals; guest and responsible waiter subscribe through
the existing scoped cursor. V15 also extends all partial unique indexes
and close-visit rules to RETURN_PENDING, so a duplicate assignment or
early closure is impossible. No fee, deposit, condition judgment, automatic
damage charge or "automatic return" is implied.

## Damaged physical game quarantine (stacked draft PR #22)

Flyway V16 adds immutable per-copy condition history with actor, timestamp,
optional originating loan, and mandatory 5-300 printable-character inspection
reason. No guest name, phone, proof, payment amount, automatic charge or
liability assumption is written to that history.

After the waiter **physically receives** an IN_USE or RETURN_PENDING unit,
they must use its exact copy code. Clean return remains supported. If damaged
or incomplete, the waiter chooses **Terima & karantina unit**, supplies an
inspection note and confirmation; the server marks both the loan RETURNED and
the physical copy DAMAGED in one transaction. The table can then close, but
DAMAGED stock never counts as available and cannot be reassigned.

Managers/admins see serialized copy status (AVAILABLE, DAMAGED, LOST,
RETIRED) through their authenticated inventory dashboard and may quarantine
an idle AVAILABLE copy or restore a DAMAGED copy after inspection. Both
transitions require the reason and audit history; other role requests fail
closed. Changes on assigned/in-use/return-pending copies are rejected, with
row locks protecting against concurrent waiter assignments. LOST/RETIRED
remain read-only because incident disposition and disposal policies are not
approved. Inventory-only state changes require manual refresh for now.

No financial or rental changes, penalties, refunds, tenant expansion, or
customer fault determination are implemented. PostgreSQL integration and
synthetic Chromium scenarios cover incorrect code, role denial, duplicate
mutation, quarantine, blocked assignment and inspected manager restoration.

## Manager condition-change audit view (stacked draft PR #23)

The manager/admin inventory page shows the 100 latest physical-copy condition
changes, newest first. Each entry includes game name, unit code, previous and
new condition, timestamp, inspection note and responsible staff login. No
guest identity, phone, order, table join proof or payment data is returned.
This is read-only reporting: deleting/editing evidence and assigning blame
are not included. A bounded no-store GET endpoint and an independently
validated Next.js server response keep the browser fail-closed.

## Shared realtime game catalogue and inventory (stacked draft PR #24)

Flyway V17 reuses the existing `live_game_signals.sequence_id` and
`/visit/live?topic=games` / `/staff/live?topic=games` SSE paths.
`GAME_INVENTORY` outbox events commit alongside manager game creation,
copy registration, waiter allocation, inspected return (including damaged
returns), and manager quarantine/restoration. The relay stores only
`game_id` and `GAME_INVENTORY_CHANGED`, never a copy serial,
condition reason, borrower, guest name, payment or staff login.
Every active authenticated guest and waiter receives the inventory
change marker, including viewers with **no loan**, while ordinary
per-loan events remain scoped to the exact owning guest/responsible waiter.
Both types share a single ordered cursor and single subscriber per page
for crash/restart and multi-replica recovery. The browser re-fetches
its authorized catalogue and inventory data rather than applying
unsafe optimistic counts. No new public/anonymous endpoints.

Tests prove that a foreign guest cannot observe a private loan request
but **does** observe catalogue stock depletion/restock; the synthetic
Chromium guest page sees a manager restore a quarantined UNO copy without
manual page navigation. This is UI freshness, not stock reservation or
proof of in-person release readiness.

## Manager inventory search and database pagination (stacked draft PR #25)

A separate, manager/admin-only `GET /api/v1/admin/games/copies/search`
supports a bounded case-insensitive substring query against **game name
or physical unit code**, optional condition filter, and a stable
25-copy page ordered by copy code and ID. The server fetches one extra
row to return `hasNext` without computing full total counts; page numbers
are zero-based, capped at 2000 to bound OFFSET work, and invalid values
fail with 400. SQL uses prepared parameters and literal-substring
`POSITION` instead of SQL LIKE wildcards. V18 adds a condition/code
index; old `GET /admin/games/copies` remains unchanged for compatibility.

The manager inventory UI uses the new paged endpoint and GET search
form, with previous/next navigation preserving query and status.
Guest, waiter, cashier and kitchen accounts cannot access individual
copy search or unit serials. Return/quarantine/restore commands
retain server-side physical-loan locks, audit and explicit reasons;
query parameters never grant any mutation authority. Inventory
realtime change signals still refresh the currently displayed page.
The V16 100-event condition-history panel is deliberately separate.
This is not unbounded export or full incident disposition.

## Server-paged inspection audit history (stacked draft PR #26)

The previous manager inspection-history panel loaded only the newest 100
condition events. A new `GET /api/v1/admin/games/condition-history/search`
provides a **full database search** by game name, unit code, staff login or
inspection reason, with a filter for resulting condition (DAMAGED or
AVAILABLE). PostgreSQL returns at most 25 records per page, newest first
(`recorded_at DESC,id DESC`), and `hasNext` via 26th-row probing.
Page numbers are restricted to 0..2000; query text has a maximum of 80
printable characters. V19 adds newest-first and condition+time indexes.

The manager dashboard has separate GET forms for copy inventory versus
condition audit: search/filter/pagination changes in one preserve the
other's selections. Query text is treated literally (not a SQL wildcard
or expression) and bound through prepared JDBC. The existing
latest-100 endpoint is retained for legacy clients. Records remain
append-only; no editing, deleting, automated liability finding, or
automatic customer damage charges are added.

PostgreSQL tests generate 53 synthetic older events to verify 25/25/3
pages, before/after filters, injection-like text handling and role gates.
Chromium HTTP/HTTPS exercises manager history search, filters and reset
while retaining the guest cash-and-game scenario. Page-based navigation
may shift when new events are recorded during browsing; keyset paging
is future work for larger-scale operational history.

## Physical game missing-copy incident workflow (stacked draft PR #27)

A waiter responsible for the visit (or cashier/manager/admin) can report a
currently allocated ASSIGNED, IN_USE or RETURN_PENDING copy as **not found**
with an explicit factual note. This records an OPEN incident, audit and
scoped GAME_LOSS_REPORTED change-only event; the loan and copy retain their
existing states and physical stock stays unavailable. Repeated OPEN reports
are rejected. An open incident blocks routine handover and inspected return
until reviewed, to prevent stale missing-unit allegations after reuse.
Unreturned loans continue to block visit closure.

Only MANAGER/ADMIN can read the incident review queue (newest 100, OPEN
first) and either DISMISS the report with an inspection note (loan stays
active; a subsequently found copy may be physically returned), or
CONFIRM_LOST after typing the exact serialized unit code and a separate
inspection finding. Confirmation atomically sets loan LOST and copy LOST,
stores signed manager/timestamp review, audit, and durable loan/inventory
signals. The unit never returns to availability, and the resolved lost
loan no longer blocks closure after the existing payment/order checks.
Neither outcome charges the guest, assigns liability, forgives an order,
transfers ownership, or automatically refunds anything. The report is
operational evidence, **not proof of customer fault**.

Flyway V20 keeps historical loan state constraints, establishes append-only
incident rows and unique OPEN report per loan, and expands existing
private-loan SSE event allowlists without payload details. Manager visibility
does not include borrower identities, contact numbers or payment fields.
Lost/retired copy recovery, physical incident appeals, evidence retention,
and liability/financial policy remain unapproved future scope.


## Verified physical recovery of previously LOST copies (stacked draft PR #28)

Physical unit discovery after a manager-confirmed loss is not an
automatic reversal of a loss decision or customer-liability claim.
`POST /api/v1/admin/games/copies/{id}/recover` requires a manager/admin
session, exact verified serialized copy code and a mandatory 5–300
printable-character note describing the physical discovery. It accepts
only a copy in LOST state with a historical LOST loan and confirmed loss
incident. The operation locks the copy, rejects outstanding active
assignments and atomically transitions **LOST → DAMAGED**, with no path
to AVAILABLE in the same operation. It appends a linked before/after
physical condition event, actor and time, audit entry and safe inventory
outbox wake-up. The original LOAN LOST, manager-confirmed incident and
financial records are preserved unmodified.

To release the recovered unit, a manager separately performs the
existing `DAMAGED → AVAILABLE` inspected restoration with a new
physical-condition reason and confirmation. This creates a second audit
history event. Found copies never become available solely because they
were located; no previously closed visit is reopened or retroactively
changed. V21 expands only the *before* condition history check to LOST,
and enforces that LOST sources go solely to DAMAGED with a non-null
historical loan. No app-facing guest endpoint, auto charge, or loss
liability determination is added.


## Manager loss incident search and pagination (stacked draft PR #29)

The manager loss register previously showed only 100 OPEN-first incident
records. A new `GET /api/v1/admin/games/loss-incidents/search` searches
**all** stored incidents with case-insensitive *literal substring* matches
against game name, serialized unit code, table code, reporting staff login,
original report note or manager review note. Optional explicit status
filters are OPEN, DISMISSED and CONFIRMED_LOST. Results are 25 rows per
page, sorted OPEN first then reported_at DESC/id DESC, with 26-row
lookahead for `hasNext`, and validated page index 0..2000. The
response also includes `openCount`, the total unresolved manager
review workload, independently of the displayed filters and page.
The manager dashboard preserves independent inventory and condition
history search state when changing incident search/status/page and vice
versa. The existing current game SSE refresh updates the displayed
incident page as events land. No new notifications to third-party
phones, personal contact fields, auto charges, silent manager actions,
or edits to historical decisions. Legacy latest-100 incident GET is
retained for compatibility. Index V22 supports OPEN-first sorting.
