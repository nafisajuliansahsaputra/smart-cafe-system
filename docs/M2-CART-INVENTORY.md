# M2: persisted cart and limited-stock policy

**Implementation status:** backend/API only on draft PR #2. No production deployment or merchant cash policy has been approved.

## Guest cart

- Authenticated `GET /api/v1/me/cart` returns the guest's cart, current item prices and availability, estimated total and monotonic cart version.
- `PUT /api/v1/me/cart` replaces the guest cart, checks expected version and validates item identity/quantities. No user-submitted prices are stored. A guest cannot see or edit another guest's cart.
- A cart is **not a reservation**. Limited stock is not held while the guest browses.
- `POST /api/v1/orders` accepts `cartVersion` for server-owned cart checkout or `lines` for backward-compatible direct checkout. A required Idempotency-Key prevents replay charges/orders. Cart checkout clears cart on success, and a replay of the original version still returns the original order.
- All snapshots and money arithmetic use the server's IDR integer price at the time of checkout.

## Atomic stock and rejection

- `item_availability` stores per-menu-item stock. A null quantity means the cafe treats that item as unlimited, NOT that the stock is unknown.
- Checkout atomically checks and decrements limited units; all lines, order rows, allocation records and payment intent commit together or roll back together.
- Submitted unapproved cash order holds the stock. OCC cashier/manager can reject it with an auditable reason **only before kitchen admission**. The system releases recorded units exactly once.
- Stock status/quantity can be adjusted by manager/admin with optimistic version, written reason and audit trail. Product page estimates are never a guarantee until checkout commits.

## Remaining risks and limitations

This is a narrow quantity-based MVP: no recipe/bill-of-material inventory, supplier purchasing, spoilage, physical stock-count reconciliation, automatic expiry of unapproved cash orders, multi-branch inventory, refund/accepted-order stock return or table-side stock adjustment UI. Changing manager counts while orders are pending may require manual reconciliation. The current model intentionally does not auto-release accepted/preparing/served orders. Do not deploy to a real merchant until these policies are agreed and validated.

Run backend tests with Docker-enabled `mvn -B -ntp verify` in `backend/` and review PR #2 GitHub CI for clean migration and concurrency evidence.
