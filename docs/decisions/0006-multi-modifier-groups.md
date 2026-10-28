# ADR 0006: Multiple independent single-select modifier groups

**Status:** Accepted for development-only M2 implementation
**Date:** 2026-10-29
**Supersedes:** ADR 0005's one-group-per-menu-item limit, not its pricing/immutability requirements

## Context

Real cafe menu items commonly require independent choices such as Size and Milk, with an optional Extra. The original M2 checkout supported only one group, and silently returning one group when two were required would make it impossible for legacy clients to create valid orders. We need a bounded, unambiguous model compatible with existing cart IDs, order snapshots and finite item stock.

## Decision

1. Allow up to eight **independent** groups per menu item. Each active group is single-select: exactly one active option if required and zero/one if optional.
2. Keep one cart line per menu item ID for this iteration. The request field `variantOptionIds` is a bounded unique UUID array. The original `variantOptionId` field remains usable alone; sending both is invalid.
3. Normalize cart option selections in `cart_line_variant_options` with FKs and backfill legacy singular choices during V9 migration. The V8 column remains only for backwards compatibility; it is not the authoritative new cart list.
4. Resolve selections and sum signed deltas using checked integer-IDR arithmetic. Verify active ownership of every option and all required groups transactionally. Persist point-in-time options[] and display label in the order's immutable variant snapshot.
5. Sort option UUIDs in the idempotency fingerprint, so reversed selection order is not a different order. Parent-item stocks remain shared across all modifier combinations.
6. Lock catalog parent item before manager edits so checkout's shared item lock stabilizes pricing snapshots. New plural public/admin group listing APIs expose all groups; legacy singular public lookup returns a 409 for multi-group configurations rather than silently losing required selections.

## Limitations

Multiple selections in a **single** group, per-option quantity, toppings inventory, multiple customized lines of one item, item bundles and post-checkout changes are deliberately not modeled. No change to real-money or browser pilot readiness is implied. Expand only through a later reviewed migration with concurrency tests.
