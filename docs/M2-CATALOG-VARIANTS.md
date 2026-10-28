# M2 catalog and single-choice variants

**Status:** Development backend/API only on draft PR #2. Do not use for a live merchant before security/policy review.

## Implemented

- Staff catalog API: MANAGER/ADMIN only, audited category create/enable/disable, menu create (HIDDEN by default), item pricing/visibility edit with revision precondition, safe public catalog filtering.
- Single-option variant groups: one group per menu item, optional or required, enabled/disabled independently. Options include a stable key, display label, signed integer-IDR price adjustment, enabled flag and revision.
- Public `GET /menu/{itemId}/variants` is available only if the menu item is visible and available; hidden/inactive options are excluded.
- Cart and direct order line accept one `variantOptionId`. Backend rejects a missing required option, another item's option or a disabled option and calculates current adjusted unit price (base + delta) and quantity total using checked integer arithmetic.
- Order line snapshot includes original selected group/key/label/delta. Cash receipt document embeds the snapshot. Owner-scoped guest and staff OCC order projections expose the original label.
- Existing cart idempotency, menu-item stock reservation, cashier rejection stock return, receipt immutability and authorization boundaries remain intact.

## Explicit limitations

This is a **single-choice MVP**, suitable for one axis such as size, not an unconstrained modifiers framework. It does not support multiple option groups on one item (size plus toppings), two separately configured lines of the same menu item in one cart, per-option stock, recipe/ingredient inventory, image/media uploads, bulk CSV edits, promotion discounts or merchant tax decisions. Changing stock counts and option pricing requires operational review for active carts/orders. There is no final visual design; staff browser catalog management is deferred.

## Acceptance and tests

Run `cd backend && mvn -B -ntp verify` with Docker for PostgreSQL Testcontainers; GitHub CI additionally checks Next.js production build and clean-start Docker Compose. The verification ledger must reference the CI run for the **current PR head**, not a previously passing commit. See [ADR 0005](decisions/0005-variant-selection.md).
