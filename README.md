# Smart Cafe System

QR/NFC-powered dine-in ordering and cafe operations platform with a customer web app, an **Order Control Center** for cashier/admin, waiter tools, kitchen display, cash/QRIS/virtual-account payments, physical board-game loans, and optional phone-based receipt delivery.

> **Status:** M1 executable skeleton is under development on a feature branch, **not yet verified or deployed**. All business workflows remain planned until implemented and tested.

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

These commands are **verification instructions, not claims of a successful run**. See [M1 verification record](docs/M1-VERIFICATION.md). The staff/order/QR/payment surfaces are not yet functional.

## Rules before coding

Read `PRD.md`, `ARCHITECTURE.md`, `AGENTS.md`, `WORKFLOW.md`, `TODO.md`, then the relevant domain spec. Keep source of truth in sync when a decision changes. **Do not invent completed status, provider credentials, transactions, or deployed environments.**

## Project ownership

Portfolio project by NATSX. License is **not yet selected**. A public repository does not implicitly grant an open-source license.
