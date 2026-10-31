# Smart Cafe System

QR/NFC-powered dine-in ordering and cafe operations platform with a customer web app, an **Order Control Center** for cashier/admin, waiter tools, kitchen display, cash/QRIS/virtual-account payments, physical board-game loans, and optional phone-based receipt delivery.

> **Current branch status (2026-11-01):** The working application has reached the stacked draft PR #58 (backend, frontend, cash/game operations, synthetic sandbox payment workflows and manager variance investigation), with exact-head CI passing. The entire feature history remains in 57 open, dependency-stacked draft PRs; the default `main` branch has **not** received these changes and no public staging or production service is deployed. Real merchant payments and receipt delivery are not enabled. For the chain audit and non-deploying staging preflight see [M6 release readiness](docs/M6-RELEASE-STAGING-READINESS.md).

## What it solves

Customers scan a table QR code or tap an NFC tag, join a live table session, enter their display name (and optionally phone number), order food/drinks, optionally request physical games delivered by staff, review the bill, and pay. Staff handle real kitchen and floor operations from protected dashboards.

| Surface | Core responsibility |
| --- | --- |
| Customer (mobile web) | Join table, menu/cart, checkout, payment, tracking, receipt |
| **Order Control Center** (cashier/manager) | Central live order queue, acceptance/exception workflows, payment supervision, refunds with permissions, search, reconciliation |
| Waiter | Open/close table, accept cash orders, deliver food and games, collect cash, returns |
| Kitchen Display (KDS) | Prepare and ready food tickets, never alter payment state |
| Admin | Catalog, pricing, tables, staff roles, game units, audit, reports, settings |

## Product principles

- **One cafe first.** Multi-branch and multi-tenant SaaS are later possibilities, not MVP requirements.
- **No required customer account.** A table visit and guest session authorize ordering.
- **QR/NFC opens a public table URL, not a permanent order credential.** Staff activate table visits; short-lived, scoped guest credentials authorize orders.
- **Order status and payment status are independent.** A waiter may approve an unpaid cash order for kitchen preparation, but cannot mark it paid until money is received.
- **The Order Control Center is mandatory, not an optional future admin screen.**
- **Games are physical assets**, reserved, delivered, and returned by staff.
- **Phone number is optional for the initial ordering flow**, useful for opt-in receipt delivery. Receipt delivery must never be a condition to complete checkout.
- Server owns prices, stock, entitlements, transactions, state transitions and payment confirmation.

## Planned technology

- Next.js + TypeScript for web interfaces
- Java + Spring Boot modular monolith for REST API and domain logic
- PostgreSQL and Flyway migrations
- Spring Security, JPA, JUnit, Testcontainers, OpenAPI
- Server-sent events (SSE) for live staff/order updates, with reconnect/snapshot recovery
- Midtrans **sandbox** payment adapter first (QRIS/VA), cash handled internally
- Transactional outbox for payment/receipt/operational notifications
- Docker Compose locally; GitHub Actions, Linux, reverse proxy, monitoring in subsequent milestones

All choices are architectural targets, not assertions of existing implementation.

## Critical customer flow

1. Staff opens a visit/session for a table.
2. Customer scans QR or taps NFC, joins the active visit, enters a nickname, optionally supplies a receipt phone number.
3. Customer selects food/drinks and can request a physical game.
4. Backend validates cart and calculates totals; customer reviews checkout.
5. Customer pays using QRIS/VA (provider-confirmed), or selects cash (staff-approved order, later collected).
6. The Order Control Center displays and routes eligible orders; KDS prepares food; waiter delivers items/game.
7. Cashier/waiter confirms actual cash collection, game is returned, all obligations close, and receipt is available in-app or optionally sent to the opted-in phone.

## Documentation index

| File | Authority / use |
| --- | --- |
| [PRD.md](PRD.md) | Product requirements, roles, scope, acceptance criteria |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Component boundaries and domain invariants |
| [AGENTS.md](AGENTS.md) | Non-negotiable rules for coding agents and contributors |
| [WORKFLOW.md](WORKFLOW.md) | Task-to-PR, CI and release process |
| [SKILL.md](SKILL.md) | Focused implementation playbook (not a runtime skill installer) |
| [TODO.md](TODO.md) | Milestones and real completion status |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | Entities, constraints, transactions |
| [docs/API-CONTRACT.md](docs/API-CONTRACT.md) | Proposed REST APIs and event envelope |
| [docs/STATE-MACHINES.md](docs/STATE-MACHINES.md) | Authoritative lifecycle rules |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, PII, payment safeguards |
| [docs/USER-FLOWS.md](docs/USER-FLOWS.md) | Customer/staff journeys, exceptions |
| [docs/RECEIPTS.md](docs/RECEIPTS.md) | Receipts, phone opt-in, delivery jobs |
| [docs/UI-GUIDELINES.md](docs/UI-GUIDELINES.md) | Customer/staff UX behavior and accessibility |
| [docs/TESTING.md](docs/TESTING.md) | Unit/integration/E2E and failure tests |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Planned environments and operations |
| [docs/decisions/0001-foundation.md](docs/decisions/0001-foundation.md) | Architecture decision record |
| [docs/decisions/0002-receipt-channel.md](docs/decisions/0002-receipt-channel.md) | Receipt delivery decision and provider gate |

## Repository layout (M1 scaffold under review)

```text
apps/web/                      # customer + staff protected route groups
backend/                       # Java Spring Boot, domain modules, tests
infra/                         # Docker, reverse proxy, observability, IaC later
docs/                          # engineering specs and ADRs
.github/workflows/             # CI/CD, introduced with executable scaffold
```

## M1 developer quickstart (verification pending)

Copy `.env.example` to `.env`, set a unique local PostgreSQL password, then run `docker compose up --build` with Docker Engine + Compose installed. Visit `http://localhost:3000` to see a diagnostic frontend that calls `GET /api/v1/system/status` on the Spring Boot API, which queries the migrated PostgreSQL schema. Run `cd backend && mvn -B -ntp verify` for JUnit + Testcontainers or `cd apps/web && npm ci && npm run lint && npm run typecheck && npm run build` for the frontend.

These commands have been **validated in clean GitHub Actions Ubuntu/Docker runners** (see [CI run 40739664242](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40739664242)); local Windows testing and any public deployment are separate. See [M1 verification record](docs/M1-VERIFICATION.md). The staff/order/QR/payment surfaces are not yet functional.

## Rules before coding

Read `PRD.md`, `ARCHITECTURE.md`, `AGENTS.md`, `WORKFLOW.md`, `TODO.md`, then the relevant domain spec. Keep source of truth in sync when a decision changes. **Do not invent completed status, provider credentials, transactions, or deployed environments.**

## Project ownership

Portfolio project by NATSX. License is **not yet selected**. A public repository does not implicitly grant an open-source license.

## M2 API vertical slice (development branch only)

Draft [PR #2](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/2) adds versioned auth, table visits, cash checkout, OCC/KDS/waiter commands, and paid cash receipts using the existing one-service modular monolith. **No real payment gateway, messaging, cash handling, or usable staff browser dashboard is connected.** A fresh local development database can opt into the one-time bootstrap described in [docs/M2-LOCAL-BOOTSTRAP.md](docs/M2-LOCAL-BOOTSTRAP.md). The test suite exercises synthetic actors and data; public demo use still requires a staging security review.

See [M2 engineering record](docs/M2-CASH-DOMAIN.md) for implemented routes, test coverage and outstanding requirements.

### Current M2 cart/stock APIs

The draft M2 backend supports `GET/PUT /api/v1/me/cart`, cash checkout from either explicit lines or a cart version, finite stock constraints, `GET/PATCH /api/v1/admin/menu-items/{id}/availability`, and audited pre-kitchen cash rejection. These operations are covered by synthetic PostgreSQL tests; no staff/guest browser UI or production pilot is enabled. See [M2 cart and stock controls](docs/M2-CART-INVENTORY.md).

### M2 catalogue and variants (backend preview)

Managers/admins can create categories and menu items, publish or hide items, revise prices with optimistic revisions, and manage a single choice group per menu item (e.g., Size: Regular/Large). Variant adjustments and required/optional choices are validated by Spring Boot, never accepted as client-supplied price. Order/receipt projections preserve the selected label and unit-price snapshot even after catalog changes. See [catalog and variants](docs/M2-CATALOG-VARIANTS.md); the browser UI is intentionally not locked in.

### Multiple modifier groups (V9 development API)

For a menu with Size (required), Milk (required), and Extra (optional), clients fetch `GET /api/v1/menu/{itemId}/variant-groups`, then submit `variantOptionIds: [sizeId, milkId]` in a cart or direct-order line. The server verifies one option per active group, enforces all required selections, and stores an immutable combined snapshot. Old `variantOptionId` still works for single-choice items. No client-supplied delta/price is trusted. [Implementation and limitations](docs/M2-CATALOG-VARIANTS.md).


## M2 staff web interface (draft PR #3)

A protected Next.js staff browser layer now exists on the separate stacked [PR #3](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/3): staff login at `/staff/login`, role-aware `/staff`, and cashier/manager/admin `/staff/orders` plus order details. The web server alone stores and forwards backend bearer tokens through an HttpOnly, SameSite=Strict, path-scoped cookie; the token is never returned to browser JavaScript. Backend authorization and financial state still own all decisions. OCC filters, approve/reject cash commands and manual snapshot refresh are available. This is **not** SSE, kitchen/waiter web UI, public staging access, or a claim that cash was paid.

See [staff browser verification and security notes](docs/M2-STAFF-BROWSER.md). PR #3 remains draft, unmerged, and not deployed.


## M2 Kitchen + Waiter operational browser (stacked PR #4)

Draft [PR #4](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/4) adds real API-backed `/staff/kitchen` and `/staff/waiter` views atop the staff login/Order Control Center in PR #3. Kitchen can start preparation and mark ready; waiter can acknowledge physical handover and record cash received after service. The backend calculates and persists exact change; the waiter view reads recorded amount/change from the database. A new `GET /api/v1/waiter/orders/tasks` API strictly scopes ordinary waiters to visits they opened, while cashier/manager/admin may supervise all. All mutations require a fresh active staff bearer session and backend authorization.

This is an **unmerged development-only slice**, not a pilot or payments certification. No live money, actual customer data or provider integrations. Manual snapshot refresh remains until SSE. See [fulfillment implementation notes](docs/M2-FULFILLMENT-UI.md).

## M2 guest ordering browser (draft PR #5)

[PR #5](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/5) adds QR/NFC table entry and temporary join proof, scoped guest browser session, available menu, required/optional modifier groups, persistent/versioned cart, cash checkout, own order tracking, and paid-only in-session receipt. Tokens never enter client JavaScript; new backend guest logout revokes the bearer, and paid receipts are fetched by owning order ID.

**Still draft, not deployed or commercial-ready.** Public join abuse throttling, authenticated browser E2E, TLS/CSRF review, staff QR proof UX, optional phone receipt provider and QRIS/VA are still required. See [M2 guest ordering verification](docs/M2-GUEST-ORDERING.md).

## M2 table management browser (draft PR #6)

[PR #6](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/6) adds an authenticated `/staff/tables` operator screen. Authorized waiters and cashier/manager/admin may open visits, rotate join credentials and close settled visits. A new role-protected `GET /api/v1/waiter/tables` exposes table availability; another waiter's active visit ID and join proof expiry are withheld. **Join proofs appear only in the staff-only response to opening/rotating a visit**, never in the table list, QR URL or browser storage. Closing a visit invokes backend settlement checks and revokes linked guest sessions.

This is still an unmerged development-only slice; see [table management acceptance notes](docs/M2-TABLE-MANAGEMENT.md).

## M2 guest join abuse guard (draft PR #7)

A new PostgreSQL-backed, fixed-window guest join throttle limits well-formed join attempts before short-lived proof verification. Counters persist even when a proof is wrong, 429 responses are safe and no-store, and a configured HMAC key prevents raw source IPs/aliases from being stored in the throttle table. It uses the actual backend socket peer, **not untrusted X-Forwarded-For**; when using the Next.js BFF that peer can be shared by many guests. This is not a complete edge per-client abuse defense. **Configure a unique `SCS_JOIN_THROTTLE_SECRET` of at least 32 bytes** in every backend environment (the .env example contains a development-only placeholder). See [M2 guest join hardening](docs/M2-GUEST-JOIN-THROTTLE.md).

## M2 browser end-to-end acceptance (draft PR #8)

A real Chromium acceptance suite now exercises `/staff/tables` → `/t/e2e-01` → guest menu/modifiers/cart/checkout → cashier approve → kitchen start/ready → waiter serve/collect cash → paid-only guest receipt → settle/close visit. Playwright uses distinct waiter, cashier, kitchen and two independent guest browser contexts against an isolated Docker/PostgreSQL app, with a **synthetic CI-only fixture**. No application path or real funds are touched in testing. CI job logs/traces are the evidence; check the exact commit before treating E2E as passing.

See [browser acceptance notes](docs/M2-BROWSER-E2E.md). This does not certify production HTTPS, edge anti-abuse or real payment gateways.


## M2 cross-role and Server Action hardening (draft PR #9)

[PR #9](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/9) adds defense-in-depth explicit role checks: guest bearer tokens remain unauthorized for staff `/auth/me` or `/auth/logout`. Next.js keeps its default same-origin Server Actions protection, now with a 32KB body size limit and explicit page security headers. Chromium negative acceptance covers foreign-Origin staff logout attempts, oversized form rejection and declining a waiter close while cash is unsettled. See [Origin and role security notes](docs/M2-ORIGIN-ROLE-HARDENING.md). Do not treat CI as a real public HTTPS proxy security audit.

## M2 HTTPS edge integration acceptance (draft PR #10)

A new **local-only** TLS reverse-proxy Compose overlay and an independent CI Chromium job exercise Next.js Server Actions through an actual HTTPS ingress, with hostile Host, X-Forwarded-Host and spoofed X-Forwarded-For tests. The edge is bound only to 127.0.0.1, uses a short-lived disposable self-signed cert and stores no keys in Git. See [HTTPS edge acceptance](docs/M2-HTTPS-EDGE-ACCEPTANCE.md). This is not a public-ready reverse proxy.

## M2 live updates (draft PR #11)

Authenticated OCC, KDS, waiter, table and guest order pages now subscribe to same-origin SSE signals using their existing HttpOnly cookies. Next.js observes backend-authorized snapshots every five seconds and sends only change signals, not order data or tokens. See [M2 live updates](docs/M2-LIVE-UPDATES.md); this is SSE via polling, not an outbox dispatcher.

## M2 transactional outbox live relay (draft PR #12)

The backend now contains a restart-safe, PostgreSQL-backed relay that converts existing committed ORDER outbox entries into durable, uniquely identified live notification signals and commit-only PG NOTIFY wake-ups. It does **not** mark unrelated business outbox deliveries processed. Docker Compose opts in; unrelated tests do not start the scheduled worker. Existing SSE/browser pages still use 5-second snapshots pending a secure cursor subscriber. See [docs/M2-DURABLE-LIVE-RELAY.md](docs/M2-DURABLE-LIVE-RELAY.md).

## M2 durable event subscriber (draft PR #13)

Guest and staff order SSE feeds now react to PostgreSQL NOTIFY-backed **authorized durable event cursors**, rather than repeatedly fetching entire order/kitchen/waiter projections. The backend uses one shared LISTEN connection and bounded role/ownership-scoped long polls; Next.js BFF keeps session credentials in HttpOnly cookies and emits empty change signals only. The table management watcher still uses authenticated snapshots. See [M2 live event subscriber](docs/M2-LIVE-EVENT-SUBSCRIBER.md).

## M2 table occupancy events (draft PR #14)

Table visit open, join-proof rotation and close now publish transactional durable events, consumed by the same authorized live cursor/SSE pipeline as ORDER. Waiters see occupancy changes without refreshing; rotation metadata is only visible to the owning waiter or supervisor, and no join proof enters notifications. See [docs/M2-TABLE-LIVE-EVENTS.md](docs/M2-TABLE-LIVE-EVENTS.md). This does not approve production capacity or deployment.

## M2 live subscription admission controls (draft PR #15)

Live event cursor long-polls now have both global and **per-authenticated-principal** budgets, so one session cannot take all available subscriptions. Internal privacy-safe Micrometer counters/gauges track active waits, exhausted capacity, PG LISTEN connectivity and durable outbox backlog; unknown backlog reads are marked -1, not silently healthy. See [M2 live capacity](docs/M2-LIVE-CAPACITY.md). This is a safety/test slice, **not** production load certification or public metrics exposure.

## M2 asynchronous long polls and synthetic performance evidence (draft PR #16)

After-cursor live requests now use Servlet async dispatch on JDK 21 virtual threads, returning the HTTP worker to the pool while waiting for a PostgreSQL event. Initial cursor reads and authorization contracts are unchanged. A dedicated disposable Docker CI benchmark checks concurrent admission/429 behavior, health responsiveness during outstanding waits, and per-staff fairness. See [M2 async live benchmark](docs/M2-ASYNC-LIVE-LOAD.md). CI observations are **not** a production throughput claim.

## Two-replica live event recovery acceptance (draft PR #17)

The repository now includes an isolated two-Spring-Boot-instance Compose overlay and a seventh CI job. It verifies cross-instance PostgreSQL notifications and recovery from a deliberately missed event while one backend is stopped, using the durable table cursor after restart. All actions use synthetic staff/table fixtures and loopback ports; no deployment or payment operation. See [multi-replica recovery](docs/M2-MULTIREPLICA-RECOVERY.md).

## M2 offline reconnect and cancellable long-polls (draft PR #18)

Authenticated backend cursor waits now use cancellation-aware Servlet DeferredResult virtual-thread workers: timeout/error/completion lifecycle callbacks interrupt abandoned waits so global/per-identity capacity is released when the container observes a disconnect. Staff and guest EventSource subscriptions close when offline or hidden, reconnect with bounded backoff and re-read latest authorized state when online. A Chromium cash-cycle regression takes kitchen offline during cashier approval and verifies automatic catch-up. See [docs/M2-DISCONNECT-RECOVERY.md](docs/M2-DISCONNECT-RECOVERY.md). Instant TCP disconnect detection is not guaranteed behind real proxies.

## M4 Physical Game Loans (Draft PR #19)

Guests can request physical UNO/board-game types during an active QR/NFC table visit. Staff assign a serialized copy, confirm code at physical handover and inspected return; outstanding requests and units block table close even after payment. Unique PostgreSQL constraints prevent allocating the same unit twice. Manager/admin can register game types and copies. First vertical slice is **free of fees/deposits**, with manual refresh; damages/losses and live notifications remain future work. See [M4 game loans](docs/M4-PHYSICAL-GAME-LOANS.md).
