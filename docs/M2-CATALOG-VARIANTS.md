# M2 catalog and multi-group single-select modifiers

**Status:** API/backend development on draft PR #2; not approved for real cafe operations.

## Current contract

Manager/admin may create categories and items (new items start HIDDEN), edit item price and status with revision checks, and configure a maximum of **eight** modifier groups per item. Every group is independently required or optional and may be disabled. Each active group permits **zero or one** active option from that group; required groups permit exactly one. Examples: Size = Large, Milk = Oat, Extras = none.

- Public catalog: `GET /api/v1/menu/{itemId}/variant-groups` returns all active groups/options on an available item.
- Manager catalog: `GET /api/v1/admin/menu-items/{itemId}/variant-groups` exposes inactive groups/options for management.
- Write contracts: existing group/option POST/PATCH API remains audited, role restricted and revision checked.
- `PUT /api/v1/me/cart` and `POST /api/v1/orders` lines accept `variantOptionIds` as an array. Legacy singular `variantOptionId` remains accepted for backwards compatibility, but both must not be provided together.
- Server checks all group constraints, option ownership, activation, and adjusted IDR unit price. Money is never taken from client-submitted prices. Selected IDs are canonically ordered for order idempotency.
- Normalized cart selection table has FK to cart line and to option. Order line stores a point-in-time `variant_snapshot` with summary `label` and individual `options[]` (group/key/label/delta). KDS, guest order detail, OCC and paid receipt use this snapshot, even after option edits.

## Compatibility

The original `GET /menu/{itemId}/variants` returns a single group only when there is at most one active group; it returns 409 for multi-group menus rather than misleading an old client about required modifiers. Old cart selections are backfilled into the normalized V9 table. The singular option JSON from older orders remains valid and readable in the projection.

## Intentionally deferred

This upgrade **does not** include multiple choices *within* one group, per-option quantities, bundles/combo deals, variant-specific stock, ingredient/recipe tracking, duplicate differently configured cart lines of one menu item, promotions or merchant tax/fees. UI is deliberately not designed here because the owner controls visual work. Full staff/browser session security and browser E2E still remain M2 release blockers.

See [ADR 0006](decisions/0006-multi-modifier-groups.md) for the migration rationale. Use exact [PR CI](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/2/checks) for verified migration and concurrency evidence.
