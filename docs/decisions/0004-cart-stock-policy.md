# ADR 0004: Checkout-time item allocation, versioned carts and pre-kitchen release

**Status:** Accepted for development-only M2 vertical slice
**Date:** 2026-10-29

## Context

The one-cafe system needs persisted guest carts, repeat-safe cash orders and prevention of last-item overselling. Supplier/recipe inventory, merchant stock-loss policy and refund workflows have not yet been approved. A frontend-only stock flag would be unsafe under simultaneous checkout.

## Decision

- Store a separate, guest/visit-scoped cart with optimistic versioning. Cart data carries quantity, not an authoritative price.
- A cart does not hold stock. Checkout takes the server price, enforces all bounded quantities transactionally and records immutable order line snapshots.
- For products with configured limited quantity, a PostgreSQL conditional UPDATE allocates stock on checkout and disallows negative amounts; null quantity represents unlimited availability.
- Store `order_stock_allocations` per order line. A staff-authorized pre-kitchen REJECTED cash order can release each allocation exactly once, with audit and a matching terminal order transition.
- Available units are exposed only as an advisory catalog/cart preview; users must handle stale-cart and out-of-stock conflicts.
- Managers can adjust stock manually using a version-checked and reason-audited command. This is not a recipe-level stock system.

## Consequences

The single DB transaction eliminates overselling the last tracked menu unit and preserves correctness on failed multi-line checkouts. An unapproved cash order consumes tracked available units until staff approves or rejects it. A timed expiry/abandonment policy, restock reconciliation, accepted-order cancellation, stock ingredients and authorized UI flows are future work. Before real cafe use, merchant inventory and cash workflows must be confirmed; this ADR is not permission to deploy.
