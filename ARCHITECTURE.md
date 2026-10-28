# Smart Cafe System — Architecture Contract

**Status:** Target design, no runtime implementation yet  
**Authority:** PRD.md > ARCHITECTURE.md > domain specs > WORKFLOW.md > TODO.md > SKILL.md.

## 1. Chosen topology

Modular monolith, one Spring Boot backend service, one Next.js frontend app with separate route groups for guest, waiter, kitchen, cashier and admin. PostgreSQL is the transactional source of truth. Separate service boundaries are **not** needed initially.

~~~text
Guest mobile + QR/NFC     Waiter      Kitchen/KDS    Cashier/OCC   Admin
             \              |             |             |         /
              +------ Next.js UI / typed API client ------+
                                     |
                              HTTPS REST + SSE
                                     |
                           Spring Boot API / Auth
  +-------------+-------------+--------------+-------------+-------------+
  | tables      | catalog     | orders       | payments    | game-loans  |
  | guests      | inventory   | fulfillment  | receipts    | staff/audit |
  +-------------+-------------+--------------+-------------+-------------+
                 | domain services + DB transactions |
                            PostgreSQL
                 | transactional outbox / worker |
              Payment Gateway          Receipt Provider
                (sandbox)              (opt-in; later)
~~~

No direct browser-to-database privileged access. Worker failures must not revert committed payment state.

## 2. Planned technology boundaries

- Frontend: Next.js, TypeScript, accessible mobile-first forms, server-originated API reads. Group routes by actor, share design primitives.
- Backend: Java, current supported Spring Boot release **validated at scaffolding time**, Spring Security, Spring Data JPA, Hibernate, Flyway, Bean Validation, OpenAPI.
- Data: PostgreSQL with normalized records, unique constraints, FK and transaction-safe state changes.
- Real-time: SSE for status and operational queue notifications. Initial delivery may be polling until event contract is implemented; never claim real-time without tests.
- External: Midtrans sandbox adapter behind PaymentGateway interface; phone receipt adapter behind ReceiptSender interface. Both have fakes for tests.
- Operations: Docker Compose locally, GitHub Actions on PR; Linux deployment and observability later.
- Deferred: Redis (only if justified), message broker, Kubernetes, separate microservices, offline cash sync.

## 3. Module responsibilities and dependencies

- identity: employee credentials, role/capabilities, guest token lifecycle.
- tables: physical table identity, visit creation/closure, join proof, guest membership.
- catalog: published item/category/variants, pricing policies, availability.
- inventory: food availability/stock reservations where enabled.
- orders: cart validation, order snapshot, idempotent submit, food/order lifecycle.
- fulfillment: KDS ticketing, waiter deliveries, service tasks.
- payments: payment intents/attempts, provider webhooks, cash capture, ledger-oriented records.
- games: board-game types, physical copies, reservations/loans, return/exception.
- receipts: final sale receipt generation, secure links, delivery preferences and attempts.
- ops: OCC queries and commands using existing domain services (not a parallel state owner).
- audit: append-only staff/security/financial action evidence.
- reporting: safe read models and aggregation with no mutation of financial truth.

Domain modules do not reach into each other's repository internals. Prefer public services/application events. State changes and corresponding outbox rows must commit together.

## 4. High-value invariants

1. **One active table visit:** unique database partial index on table_id WHERE status='ACTIVE' (adjust actual status model if necessary).
2. **Scoped guest:** a visitor can only view/mutate own guest-owned carts/orders within current visit; do not trust a client-provided table_id/role.
3. **Checkout snapshot:** server calculates IDR prices/taxes/fees; store immutable line and total snapshots; use numeric decimal/integer representation.
4. **Idempotency:** key scoped to actor/action with request hash and uniqueness; repeat gives same result, mismatched payload fails.
5. **Payment truth:** server/provider-verified payment, duplicate and out-of-order notifications ignored/handled safely; attempt-level errors do not overwrite a confirmed paid aggregate.
6. **Cash truth:** cash approval authorizes work, not PAID. Cash paid only after cash tender recorded by permitted staff with auditing.
7. **Game copy uniqueness:** reserve a physical unit in one transaction; prevent overlapping active reservations/loans and prevent duplicate delivery.
8. **Table closure:** reject close with unsettled liabilities or unreturned games unless audited manager override per policy.
9. **Receipt truth:** finalized receipt only after confirmed PAID; one immutable version per finalized sale, corrections as explicit void/credit events.
10. **Outbox:** external notifications retry with dedupe; no fire-and-forget HTTP used as reliable delivery.

## 5. Command and read flow

Order submission:
- Authenticate guest table visit; validate order request and anti-replay key.
- Lock or atomically update availability; calculate pricing server-side.
- Insert order, item snapshots, payment intent/state and audit/outbox in DB transaction.
- Return stable order_id. Trigger provider creation through controlled adapter, storing response/recovering failures.
- For cash: order enters staff-approval queue. For digital: kitchen admission waits for verified provider confirmation.
- OCC command for cash approval records actor and sends an eligibility event.
- KDS only consumes admitted eligible orders. No client-enforced admission.

Receipt:
- At PAID commit create or ensure immutable receipt data.
- Create dispatch job only if customer opted into approved channel, otherwise offer in-app receipt.
- Worker sends link/template idempotently; attempts recorded; provider unavailable => pending/failed without affecting order.
- Tokenized receipt access has expiry, scoped claims and no exposed phone.

## 6. API and event style

- REST JSON /api/v1/... with explicit error codes and server-generated IDs.
- Idempotency-Key header for checkout, cash capture, provider-creation retry, financial mutation.
- Cursor/offset pagination as specified per endpoint, authorization before filtering.
- SSE staff streams authenticated, snapshot bootstrap and reconnect on disconnection; never assume stream event is canonical truth.
- Webhook under separate strictly verified and size-bounded endpoint.
- No provider client secret in browser. Payments cross trust boundary only through backend.

See docs/API-CONTRACT.md, docs/STATE-MACHINES.md and docs/SECURITY.md.

## 7. Persistence and consistency

Migrations via Flyway; use FK, CHECK, UNIQUE, appropriate indexes and transaction isolation. Demonstrate race tests for last available menu stock, duplicate checkout, concurrent game loan, and session closure.

Avoid cross-module distributed transactions; when external provider side effects occur, use retriable state machine and reconciliation. Never hold a DB transaction open during slow external network calls.

## 8. Runtime boundaries and routes (planned)

- /t/[tableAlias] : public QR/NFC entry and join
- /visit/* : guest menu/cart/payment/status/receipt (scoped auth)
- /staff/waiter/* : table visits, requests, deliveries, cash
- /staff/kitchen/* : preparation tickets
- /staff/orders/* : **Order Control Center** cashier and manager
- /staff/cashier/* : cash transactions and reconciliation
- /staff/admin/* : catalog, staff, settings, reporting, audit

Server route access alone is not authorization. Backend must enforce resource-level policies.

## 9. Security/operations architecture

TLS, HttpOnly/Secure cookies where used, CSRF for cookie-auth mutations, short-lived guest session, safe refresh/rotation, rate limits, restricted CORS, staff role enforcement, sensitive field projection, structured logs and audit logs.

Define secret sources, backups, health probes, metrics, and rollback before staging pilot. Do not log guest phone, payment secrets, provider auth, receipt-link token, or session credentials.

## 10. Repository target (future structure)

~~~text
apps/web/                     # Next.js web and route groups
backend/src/main/java/...     # Spring Boot modules
backend/src/test/...          # unit/integration tests
backend/src/main/resources/db/migration/ # Flyway migrations
infra/                        # compose, deployment, monitoring
docs/decisions/               # ADR
.github/workflows/            # CI
~~~

No folder in this section claims to exist today; TODO tracks execution truth.

## 11. Decision discipline

Prefer reversible steps and documented decisions. When changing technology, storage, provider, financial status, role permissions or table/game semantics, write an ADR and update PRD, domain spec, tests and TODO. Avoid speculative infrastructure or unsupported provider assumptions.
