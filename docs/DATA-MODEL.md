# Data Model — Smart Cafe System

**Status:** Logical contract plus an implemented subset on unmerged M1/M2 feature branches. Flyway V1–V5 are committed and applied successfully in CI. Future entities and indexes below remain design targets. PostgreSQL is authoritative.

## 1. Identity and tables

- cafe_tables: id PK; table_code unique; public_alias unique unguessable; capacity; status; created_at.
- table_visits: id PK; table_id FK; status; opened_by FK staff; opened_at; closed_at; join_secret_hash/rotation_version; close_override_by/reason nullable; version.
- visit_guests: id PK; visit_id FK; display_name; phone_e164_encrypted nullable; phone_last4 nullable; receipt_channel_preference nullable; receipt_opt_in_at nullable; consent_notice_version nullable; joined_at; credential_version.
- staff_users: id PK; login identifier; password_hash or external auth identifier; enabled; created_at; updated_at.
- staff_roles: id; role_key; staff_role_assignments: staff_user_id, role_id; permission policy version.

Constraint: a table has at most one visit in ACTIVE/CLOSING. Alias is not credential; guest tokens are server-issued and visit-scoped. Do not use phone as guest login.

## 2. Catalog and order

- menu_categories: id; name; slug; sort_order; active.
- menu_items: id; category_id; name; description; base_price_idr; tax_profile_id; availability; image_key nullable; revision.
- menu_variants: id; menu_item_id; variant_key; label; price_delta_idr; required/selection rules.
- item_availability: menu_item_id; available; limited_quantity nullable; version; optional inventory reserve rules.
- carts: id; guest_id; visit_id; status; updated_at; optimistic_version.
- cart_lines: cart_id; menu_item_id; variant_snapshot_selection; quantity; customer_notes.
- orders: id PK; display_number unique; visit_id FK; guest_id FK; status; currency=IDR; subtotal_idr; discount_idr; tax_idr; service_fee_idr; total_idr; pricing_policy_version; created_at; accepted_at; version.
- order_lines: id; order_id; menu_item_id nullable; name_snapshot; variant_snapshot; unit_price_idr; quantity; line_total_idr; notes; tax_snapshot.
- order_idempotency: actor_id+scope+key unique; request_hash; order_id; created_at; expiry.

IDR stored in **integer rupiah** for now with checked bounded arithmetic (nonnegative, overflow-safe); if future currencies need fractions, migrate deliberate type. Snapshot price/tax values on order, not just mutable menu refs.

## 3. Payments

- payment_intents: id; order_id unique FK; method_selected; amount_due_idr; amount_paid_idr; aggregate_status; currency; version; created_at.
- payment_attempts: id; intent_id; provider; provider_order_ref unique nullable; provider_transaction_id unique nullable; method; status; expiry; response_metadata_safe; created_at.
- payment_events: id; provider; provider_event_fingerprint unique; attempt_id; normalized_status; signature_verified; arrived_at; applied_at; redacted_payload_ref; outcome.
- cash_tenders: id; payment_intent_id; staff_user_id; amount_received_idr; change_given_idr; recorded_at; terminal_id optional; correction_of nullable.
- payment_adjustments: id; payment_intent_id; kind; amount_idr; reason; approved_by; provider_ref nullable; recorded_at.
- payment_reconciliation_cases: id; payment_intent_id; reason; status; assigned_to; resolved_at.

Constraints: payment money ledger is append-only; no mutable direct overwrite for received tender. Paid aggregate only from authorized DB command following verified provider event or cash tender receipt. Unique provider refs and idempotency keys. Atomic update/locks prevent two success paths.

## 4. Kitchen/service and game inventory

- kitchen_tickets: id; order_id; status; admitted_at; preparing_at; ready_at; version.
- delivery_tasks: id; order_id nullable; game_loan_id nullable; type; assigned_waiter_id nullable; status; completed_at.
- games: id; title; description; recommended_players; rules_url optional; active; fee_policy nullable.
- game_copies: id; game_id; copy_code unique; condition; availability_status; notes; version.
- game_requests: id; visit_id; guest_id nullable; order_id nullable; game_id; status; created_at.
- game_reservations: id; game_request_id; game_copy_id; hold_until; status; created_at.
- game_loans: id; reservation_id unique; visit_id; copy_id; delivered_by; delivered_at; returned_by; returned_at; condition_at_return; status.

DB enforces **no overlapping active reservations/loans for the same copy** with appropriate unique partial index and locking strategy, plus transaction-level recheck. Historical returns remain retained.

## 5. Receipts, messages and audit

- receipts: id; payment_intent_id; order_id; receipt_number unique; total_idr; currency; issued_at; immutable_document_json; supersedes_receipt_id nullable.
- receipt_access_tokens: id; receipt_id; token_hash; expires_at; revoked_at; max_uses optional.
- receipt_delivery_preferences: id; guest_id; channel; consent_version; opted_in_at; revoked_at; destination_encrypted.
- receipt_dispatches: id; receipt_id; channel; destination_ciphertext; status; dedupe_key unique; attempt_count; next_attempt_at; provider_message_id nullable; last_error_code nullable.
- outbox_events: id; aggregate_type/id; event_type; payload_safe; created_at; locked_until; processed_at; retry_count.
- audit_logs: id; actor_type; actor_id; operation; resource_type/id; reason nullable; correlation_id; safe_before_after_diff; occurred_at.

Use a privacy-safe receipt display: masked phone, no gateway secrets, no full card/account data. Audit logging is append-only; access controlled.

## 6. Entity relationships

~~~text
CafeTable 1 -> N TableVisit 1 -> N VisitGuest 1 -> N Order
Order 1 -> N OrderLine
Order 1 -> 1 PaymentIntent 1 -> N PaymentAttempt 1 -> N PaymentEvent
Order 1 -> 0..1 KitchenTicket 1 -> N DeliveryTask
PaymentIntent 1 -> 0..N Receipt (correction history)
TableVisit 1 -> N GameRequest 0..1 -> GameReservation -> GameLoan -> GameCopy -> Game
~~~

## 7. Migration and transactional rules

- All schema changes in Flyway, never manual production dashboard-only changes.
- Use non-null constraints/foreign keys/check constraints and tested partial unique index.
- Currency/total rounding and tax rules defined and versioned before production.
- Use optimistic version for ordinary edits, row lock or atomic update for monetary and last-copy allocation.
- Do not store provider server keys, session tokens or plaintext phone in tables.
- Use outbox rows created in the same DB transaction as finalized sale and delivery events.
- Retention/deletion/financial-legal exceptions must be defined as a policy before real customer data; do not hardcode an arbitrary regulatory duration.

## 8. Suggested indexes to verify with EXPLAIN

- table_visits(table_id) unique for active statuses.
- orders(visit_id, created_at DESC), orders(status, created_at DESC).
- kitchen_tickets(status, admitted_at), delivery_tasks(status, assigned_waiter_id).
- payment_attempts(provider_order_ref), payment_events(provider_event_fingerprint).
- game_reservations(game_copy_id) active-only unique.
- receipt_dispatches(status, next_attempt_at), outbox_events(processed_at, created_at).
- audit_logs(resource_type, resource_id, occurred_at).

Index choices should be measured after query shape and actual EXPLAIN plans exist.

## 9. Implemented migrations (M2 development branch)

- **V1**: staff, roles, tables/visits, guests, catalog base items, immutable order line snapshots, payment intents, cash tender, kitchen tickets, audit and outbox skeleton. The partial unique index prevents a second ACTIVE/CLOSING visit on the same table; composite FK ties order guest to visit.
- **V2**: hashed revocable staff bearer sessions, case-insensitive staff login uniqueness, database-backed login throttling.
- **V3**: short-lived join proof expiry and guest bearer sessions scoped to active visits, all stored as SHA-256 hashes of 256-bit random tokens.
- **V4**: immutable paid-cash receipts, plus a DB trigger forbidding mutation of order financial snapshot fields.

Not yet implemented: variant tables, inventory reservations, payment attempts/webhooks, serialized physical game units and loans, outbox delivery worker, receipt message dispatch and full reconciliation. A passed migration is not evidence that those future entities exist.

- **V5**: Database receipt insertion guard requires matching payment intent, order, total and fully PAID status; the trigger rejects prepayment receipt creation even if application code attempts it.

## 10. Cart and stock migrations

- **V6** adds `carts` keyed by unique guest + visit, `cart_lines` with unique item and bounded quantity, and `item_availability` with `limited_quantity BIGINT NULL` (null is unlimited, never inferred as zero). Every menu item receives an availability row via backfill and insert trigger. DB enforces nonnegative remaining quantity.
- **V7** adds `order_stock_allocations(order_id,menu_item_id,quantity,reserved_at,released_at)`. Checkout decrements bounded stock in the same transaction as creating the order and allocation record. A pre-kitchen rejection returns counted allocations exactly once and records the release, order transition and staff audit in one DB transaction.
- The catalog's `menu_items.availability` status is distinct from the quantitative `item_availability` row. The public menu filters both. A stored cart is not a stock reservation.
- This stock model is per menu-item unit, not ingredients/recipes and not yet a full warehouse ledger. Inventory adjustments during an outstanding checkout require operational reconciliation and a reviewed policy before real stock use.

## 11. V8 single-choice variants

- `menu_variant_groups`: id, unique menu_item_id, name, required, active, revision. One group maximum per item in this M2 version.
- `menu_variant_options`: id, group_id, unique option_key per group, label, signed price_delta_idr with bounds, active, revision.
- `cart_lines.variant_option_id` is nullable with FK to option; checkout also verifies it belongs to the item and enabled group. A missing choice is accepted only when no enabled required group exists. Cart still permits one line per menu item. Multiple modifier groups and multiple differently configured lines of one item require a future explicit schema migration.
- The immutable `order_lines.variant_snapshot` captures the effective selected option metadata and signed delta while `unit_price_idr` stores final server-computed unit price. Receipt document JSON includes the option snapshot, and guest/OCC order detail exposes the selected label.

## 12. V9 normalized modifier selections

- V9 removes the database unique constraint on `menu_variant_groups.menu_item_id`, replacing it with a unique index on `(menu_item_id, lower(name))`. Application service currently restricts a menu to eight groups.
- `cart_line_variant_options(cart_id,menu_item_id,option_id)` is normalized with a composite FK to cart lines, single option FK, and cascade delete when a cart line is consumed or removed. Migration backfills existing singular `cart_lines.variant_option_id` selections. Old singular column remains as a compatibility field, but the normalized junction is authoritative for new checkout.
- Cart and direct checkout lines retain a single menu item and quantity, plus zero or more selected UUIDs, capped at eight. Required group cardinality and option-group ownership are enforced in transactional domain service, not inferred from a UI. One per active group, not unrestricted multiple toppings.
- Order `variant_snapshot` is immutable order-time JSON. New selection objects contain `label` and `options[]` with group name, option key/label and signed IDR delta. Old historical single-option JSON can coexist; the projection label remains present in both.

## V13 serialized physical games (draft PR #19)

`physical_games(id,name,description,active)` is the title/catalog layer. `physical_game_copies(id,game_id,copy_code UNIQUE,condition)` holds individually identifiable units, with default AVAILABLE and future DAMAGED/LOST/RETIRED conditions (no incident UI yet). `physical_game_loans(id,visit_id,guest_id,game_id,copy_id,status,requested_at,assigned_at,handed_over_at,returned_at,assigned_by,handed_over_by,returned_by)` holds the transaction and audit actor references. A compound FK `(copy_id,game_id)` ensures unit/type consistency; a partial unique active-copy constraint allows only one ASSIGNED/IN_USE loan per unit; an active (visit,game) constraint prevents duplicate pending loans. Close checks any REQUESTED, ASSIGNED or IN_USE loan. Receipt/order tables remain unchanged.

## V24 digital payment attempt foundation (draft PR #44)

`digital_payment_attempts` is an isolated append/evolving-status table for **future** authorized QRIS/VA intent attempts. Columns: opaque id, payment_intent_id, method, provider_key/reference, idempotency_key, amount_idr, currency, CREATED/PENDING/SUCCEEDED/FAILED/EXPIRED/CANCELLED state, created_at/updated_at/expires_at. Uniqueness prevents the same provider reference or scoped idempotency key twice and more than one unresolved attempt per intent. A database trigger validates every attempt's selected method/amount/currency against its payment_intent and prohibits mutation of financial identity and terminal-state regression. Foreign keys and indexes are migration-managed. There is no provider callback/event history or reconciliation model yet; do not interpret this schema as a functioning QRIS/VA checkout.
