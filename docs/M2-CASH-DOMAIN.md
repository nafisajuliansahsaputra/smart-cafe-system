# M2 cash vertical slice: development implementation ledger

**Status:** Backend API vertical slice under development on draft PR #2. It has passed CI using synthetic data, but no real cafe/POS deployment has occurred.

## Implemented

- Revocable staff accounts (BCrypt hash), 8-hour hashed bearer sessions, role checks and DB-persistent login throttling. No stock credentials.
- Table visit open/close, 10-minute rotating join proof, 8-hour visit-scoped guest bearer session, close guard for pending/unpaid orders; visit actions are audited.
- Public menu endpoint for published available base-priced items, guest-owned cash checkout with integer IDR calculations, immutable line/total snapshots and PostgreSQL-backed concurrent idempotency.
- Authenticated OCC status/payment/table/order-ID filtered queue, itemized detail and approve command, KDS ticket admission/start/ready, waiter serve, unchanged AWAITING_CASH after approval/serving.
- Staff cash capture requiring SERVED, auditable tender and change, payment PAID in the same transaction as issued immutable receipt, safe owner-only receipt fetch. Durable outbox rows are committed for order events; dispatcher remains future work.
- CI passed 23 JUnit/Testcontainers tests plus Next.js checks and Docker Compose clean-start. [Evidence](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40744023293).

## Important limitations

All M2 screens are **not** implemented. Frontend staff routes still return 404; only the API is operational. Admin catalog/staff CRUD, persisted cart, menu variants, stock reservation/locking, OCC exception commands and browser UI, rate limiting around guest joins, KDS/waiter visual dashboards and browser end-to-end tests remain open. These are not claimed complete in TODO.

Cash-at-handover and zero tax/fees are explicitly **development-only conservative rules**, not a commercial merchant policy. The owner must decide cash payment point and applicable taxes/fees. No Midtrans, WhatsApp/SMS provider or real funds are handled.

The local first-admin bootstrap is opt-in and documented separately; it is not a production onboarding process. Use disposable synthetic data only.

## Follow-on engineering order

1. Harden auth/session/visit endpoint abuse controls, test true concurrent cash tenders and denied/expired credentials.
2. Persisted guest cart, variants and inventory-checked ordering.
3. OCC order detail/search/exception commands and real KDS/waiter task screens.
4. Guest receipt UI/print, role-managed staff bootstrap/admin, full browser E2E and staging security gates.

## Additional hardening

V5 introduces a PostgreSQL receipt guard: a receipt can only be inserted for a matching fully PAID intent and amount. A waiter may close/rotate a visit, serve its order or collect its cash only if that waiter opened the visit; cashier/manager/admin retain controlled supervisory privileges. A two-way concurrent cash tender integration test confirms one payment and one receipt only. Task reassignment and staff UI remain pending.

## V6/V7 guest cart and inventory controls

Persisted, guest-scoped cart supports GET/PUT with version checks. It is an editable draft, not a stock reservation. Cash checkout accepts an explicit cart version, maintains same-key replay after cart consumption, and checks/consumes limited menu stock in the same transaction as order insertion. The public menu hides sold-out items; admin inventory adjustments require manager role, version and an audit reason. Unapproved cash orders can be rejected with reason; recorded stock allocations are restored only once. See [inventory notes](M2-CART-INVENTORY.md) for precise limitations.

The read-only `GET /me/orders/{id}` endpoint exposes guest-owned order status and immutable item snapshots (404 for another guest); a successful cash checkout appends `ORDER_SUBMITTED` to the database outbox. Frontend guest status/print UI is a separate remaining task.

## Catalog and selected option snapshot

Flyway V8 and `VariantCatalogService` implement one configurable group per item, with optional/required single choice and auditable manager edits. Customer catalog choice is not a trusted price; `VariantPricingService` checks the active item/group/option and the cash checkout commits the adjusted unit price plus immutable variant snapshot. Receipt documents include the selected variant, and guest/OCC projections display its original label. Multi-group/custom modifiers and variant-level inventory are deferred. [Engineering details](M2-CATALOG-VARIANTS.md).
