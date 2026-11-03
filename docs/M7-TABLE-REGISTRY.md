# M7: Real cafe table registry and safe physical table activation

## Why

The full-stack waiter dashboard already opened/closed real active visits but
relied on predefined `cafe_tables` records. Managers need to provision
physical tables from the actual website, not insert database rows by hand.

## Functionality

- MANAGER and ADMIN: open `/staff/tables/registry`, create a permanent
  table code and URL alias, set capacity, and change availability.
- WAITER/CASHIER/KITCHEN: registry route is unavailable (HTTP 404).
- After creation, `/t/{publicAlias}` becomes the permanent QR/NFC
  destination. The alias is immutable to avoid invalidating printed tags.
- Manager/admin can use `/staff/tables/registry/print` to make an A4 sheet
  of permanent table QR signs or download individual print-quality SVGs.
  The QR encoder is vendored locally from Project Nayuki under its MIT license:
  no third-party QR API receives the URL, and no new npm dependency is needed.
- The generated URL uses the **current browser origin**, not untrusted forwarded
  Host headers. A localhost QR is only usable on that machine; an operator must
  open the studio through the verified public HTTPS cafe host for physical
  placement, confirm the hostname and test scanning from an actual phone.
  Print batches paginate at eight tables so the browser is not forced to
  render hundreds of QR symbols at once.
- NFC tag writers use the same public URL shown on the printed card.
  The studio does not write NFC hardware tags.
- A printed table URL does not authorize ordering. A real waiter must
  open an active visit and provide a temporary join proof out of band.
- No destructive table deletion exists. Out-of-service keeps historical
  visits and receipts unchanged.

## Transaction and security rules

`TableAdminController` exposes manager-role-protected POST and PATCH
under `/api/v1/admin/tables`. `TableAdminService` validates canonical
codes, lowercase slug aliases, seat capacity 1..30 and a strict
AVAILABLE/OUT_OF_SERVICE enum.

Changing an existing table is protected by a transactional
`SELECT ... FOR UPDATE` of the `cafe_tables` row and the expected
prior status/capacity supplied by the client. This is the **same lock**
that `TableVisitService.open` acquires; concurrent visit opening and
manager deactivation therefore serialize. A proposed OUT_OF_SERVICE
state fails with HTTP 409 when any ACTIVE/CLOSING visit exists, even if
the manager interface holds an earlier snapshot. All successful changes
write audit events in the same database transaction.

The interface sends requests through server-only Next.js actions with
HttpOnly-bound staff bearer verification and explicit confirmation.
The browser never learns a raw bearer token. Both the page and Spring
Security independently require manager/admin roles.

## Verification

The synthetic browser suite exercises actual Spring/PostgreSQL:
create a table, verify its public alias, change capacity, deactivate
and reactivate, open a real visit from the waiter browser, then submit
a stale manager attempt to deactivate it and confirm the server rejects
the operation. It also checks cashier/kitchen/waiter route isolation and
320-pixel overflow, and captures a sanitized manager screen.

## Remaining whole-website scope

Physical print/scan and NFC field verification, visual accessibility
review, 250+ table registry pagination, real merchant QRIS/VA settlement,
approved receipt sender, hosted staging isolation and production
credential/backup/monitoring and branch protection remain separate
requirements. This increment is real operational software, not the
portfolio `/showcase` simulation.

## QR studio acceptance (follow-up draft)

The manager-only QR studio runs entirely in the browser after a role-gated,
backend-validated table inventory read. Its SVG encoding uses medium error
correction and a four-module white quiet zone. The exported vector and printed
card contain only the permanent `/t/{publicAlias}` URL, never temporary join
proofs or authenticated staff URLs. A 320px browser test exercises one-table
rendering and file download; regular cashier, waiter and kitchen accounts must
get HTTP 404. Run the exact-head Chromium, Next.js build and security CI before
calling this a verified release. A physical scan/print pilot is still required.

## Server-side registry pagination and search (stacked follow-up)

The manager-only inventory now reads `GET /api/v1/admin/tables/registry?page=&q=`
instead of reusing the waiter floor-board endpoint (which still deliberately
returns at most 250 operational tables). Each request fetches 25 rows from
PostgreSQL with a stable table-code/UUID sort, a total count, and a `hasNext`
marker. Search matches only literal code/alias substrings (case-insensitive);
underscores are not wildcard patterns, and inputs/pages are bounded.

The read projection contains **only** public table identifiers, capacity,
status and a Boolean active-visit indicator. It cannot disclose a join proof,
visit identifier, customer phone, staff token or payment data. Manager/admin
roles are enforced by Spring Security and the Next.js page; ordinary staff
receive a denied response. Errors and invalid pages fail closed.

The QR studio uses the same paginated projection. Managers can navigate
database batches and print the QR sheets for each batch instead of silently
omitting tables after the first 250. The print controls stay off the A4 output.
A 28-row PostgreSQL integration regression verifies page boundaries,
literal search, no private fields and staff-role denial; Chromium exercises
manager search and QR filtering with the real disposable database.
The waiter live-floor board remains separately bounded for now.
