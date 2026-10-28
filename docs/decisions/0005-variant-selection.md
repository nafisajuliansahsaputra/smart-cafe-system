# ADR 0005: Single variant group for first cash-order slice

**Status:** Accepted for development-only implementation
**Date:** 2026-10-29

## Context

The canonical data model calls for menu variants with a key/label, signed price delta and selection rules. M2 already uses a versioned cart keyed by guest and menu item, bounded quantity and an immutable order-line price snapshot. Shipping arbitrary multi-group modifiers without documented combination cardinalities or variant-level stock would risk incorrect prices and order ambiguity.

## Decision

1. Model at most one variant group per menu item, with at most one chosen option. The group may be required or optional. An item without an active required group may be ordered with no choice.
2. Each enabled option belongs to one group and has a unique stable key. An inactive/foreign option is rejected server-side. No client-supplied price/delta is accepted.
3. Use checked integer-IDR arithmetic on base price plus signed option delta. Reject negative adjusted unit price or overflow. The existing menu-item stock ledger applies to all options of the same item.
4. Persist selected option on cart lines, include its UUID in direct-order idempotency fingerprints, and snapshot original option ID/name/label/delta and final unit price on order lines. Paid receipts embed the immutable snapshot.
5. Only manager/admin staff can create/configure groups/options, with audit and optimistic revision checks. No destructive delete is supported.

## Consequences

One size choice works without rewriting the existing cart or order identity model. Supporting combinations, customization notes, variant-specific stock or duplicate item lines will require a deliberate V9+ migration, revised checkout canonicalization, and concurrency tests. This ADR is not a permanent product restriction or merchant policy, and it does not authorize production deployment.
