# M7: Real staff catalog operations

## Purpose

The portfolio marketing and `/showcase` screens are explicitly simulation-only.
The full application also needs real manager/admin management of products
and quantities in PostgreSQL. This release increment adds that actual
operational staff interface using the existing audited Java APIs.

## Routes and permissions

- `/staff/catalog`: category list, status toggle, category creation, search,
  item listing and creation.
- `/staff/catalog/[itemId]`: item name/category/price/visibility, limited
  stock, optional or required modifier groups and selectable options.
- Only `MANAGER` and `ADMIN` may load these pages. Other staff roles get
  HTTP 404, and the Spring backend independently protects all
  `/api/v1/admin/**` reads and writes.
- Every mutation is a server action with a token read exclusively
  server-side. The browser never receives a staff API bearer token and
  there is no open pass-through proxy.

## Real data and safeguards

- Reads validate the shape of the API envelope before rendering. A failed
  category, item, variant or stock read halts edit controls instead of
  displaying a fake empty list.
- New menu items are created as `HIDDEN`. Operators must configure stock
  and any necessary options before manually switching to `AVAILABLE`.
- Item edits include `expectedRevision` and stock adjustments include
  `expectedVersion`; stale updates are rejected with HTTP 409 and a
  refresh warning. Group/option edits use their own expected revisions.
- All form values (UUID, names, slug, whole IDR prices, availability,
  stock quantity, modifier delta and reason) are validated before
  submitting JSON to the existing backend.
- Stock adjustments require a reason and explicit physical-stock
  confirmation. The backend logs the staff audit event.
- A category can be disabled without deleting its order history. Product
  and modifier edits do not mutate previously finalized order snapshots.
- The user interface does **not** expose deletion of products,
  historical orders, receipts or catalog inventory records. This is an
  intentional conservative default.
- Backend item listing currently caps results at **500**. The UI
  paginates the returned list, but a production cafe above that limit
  requires a server-side cursor/filter endpoint before claiming full
  administrative completeness.

## Acceptance

The synthetic Playwright browser suite:

1. Logs in as manager and confirms the dashboard catalog link.
2. Creates a category and hidden item through the real Next.js server
   actions and the running Spring Boot/PostgreSQL service.
3. Creates a required variant group and price-adjusted option.
4. Changes the item's audited limited stock to 12 and publishes it.
5. Verifies live rendered state and narrow 320-pixel layout.
6. Ensures cashier, waiter and kitchen sessions cannot load the
   manager-only catalogue and item detail routes.
7. Captures a sanitized manager inventory screenshot into the synthetic
   portfolio screenshot artifact.

This test runs on disposable Docker services and does **not** create
real merchant transactions, live staff accounts or public admin access.

## Remaining whole-product scope

The real app remains unreleased as long as the following are incomplete
or unapproved: human acceptance of the complete draft PR stack, protected
main and production credentials, genuine merchant QRIS/VA + bank
reconciliation, opt-in phone receipt dispatch and sender approval,
production-grade hosted HTTPS isolation and backups, accessibility
testing on physical devices, and staged operator pilot/recovery drills.
A static showcase landing is not a substitute for any of these.
