# Data Model — Smart Cafe System

**Status:** Logical schema / design proposal; Flyway SQL migrations will be created in M1. Names and indices are targets subject to reviewed migration. PostgreSQL is authoritative.

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
