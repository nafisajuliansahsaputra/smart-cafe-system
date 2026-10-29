# Smart Cafe System

QR/NFC-powered dine-in ordering and cafe operations platform with a customer web app, an **Order Control Center** for cashier/admin, waiter tools, kitchen display, cash/QRIS/virtual-account payments, physical board-game loans, and optional phone-based receipt delivery.

> **Status:** M1 executable skeleton is CI verified on a separate feature branch; draft M2 PR #2 now includes API-backed staff/table/guest/cash ordering flows with PostgreSQL integration tests. Neither PR is merged or deployed. Complete UI/operational coverage is still in progress.

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

[PR #9](https://github.com/nafisajuliansahsaputra/smart-cafe-system/pull/9) closes a principal-type authorization gap: authenticated guests can no longer call staff `/auth/me` or `/auth/logout`. Next.js keeps its default same-origin Server Actions protection, now with a 32KB body size limit and explicit page security headers. Chromium negative acceptance covers foreign-Origin staff logout attempts, oversized form rejection and declining a waiter close while cash is unsettled. See [Origin and role security notes](docs/M2-ORIGIN-ROLE-HARDENING.md). Do not treat CI as a real public HTTPS proxy security audit.
