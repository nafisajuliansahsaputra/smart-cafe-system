# Smart Cafe System | Portfolio Case Study

**Project:** Smart Cafe System
**Type:** Self-initiated full-stack portfolio project
**Product stage:** Functional MVP code + isolated interactive product showcase; no real merchant processing
**Author/brand:** NATSX
**Code status:** Integration candidate in draft PR #61, not merged to main

## Problem and product direction

An ordinary cafe uses separate order notes, kitchen instructions, table records and cash sheets. A busy shift makes it easy to lose the relationship between table, order, staff and payment. Smart Cafe System explores how the full dine-in lifecycle could be coordinated with one source of truth.

The experience is intentionally divided into four roles:

- **Customer:** no required account; table QR/NFC plus an active visit credential; menu, cart, order progress and paid-only receipt.
- **Cashier / OCC:** accept/reject orders and reconcile cash, with payment and preparation modeled as distinct states.
- **Kitchen / KDS:** see only eligible tickets and advance preparation without gaining financial privileges.
- **Waiter:** manage visits, serve food, collect actual cash and handle serialized physical board-game loans.

The first implementation targets **one cafe**, not a multi-tenant SaaS.

## Engineering approach

| Layer | Chosen stack / responsibility |
| --- | --- |
| Customer and staff web | Next.js 16, React 19, TypeScript, accessible role-specific routes |
| Backend | Java 21, Spring Boot modular monolith, REST and SSE |
| Transactional data | PostgreSQL 17 with Flyway migrations and database constraints |
| Auth and permissions | Scoped guest sessions, revocable staff sessions, backend role/capability checks |
| Financial logic | Server-authoritative cash state and immutable paid-only receipts; fake-only digital attempts |
| Verification | JUnit, Testcontainers, Playwright/Chromium, GitHub Actions, CodeQL, Trivy, Maven advisory scan |

The database is the source of truth. A successful browser interaction or an unverified payment callback cannot create a paid order. Server-side lifecycle transitions use transactional writes and explicit permission checks. The kitchen is allowed to prepare an approved **cash-due** order, but that approval **never** means payment has been collected.

## Demonstrable flows

1. An authorized waiter starts a table visit and reveals a time-limited guest join proof.
2. The customer joins the table without an account, chooses items and places a cash order.
3. The OCC receives the new order, approves work, while the cash balance remains unpaid.
4. The KDS prepares the eligible ticket, and the waiter serves it.
5. Authorized cash capture updates payment truth; only after confirmation does the guest receive the immutable receipt.
6. Physical games can be allocated by serialized unit, handed over and returned, with inventory/incident review.
7. Separate cashier shifts, summary reports and fake-payment reconciliation support operational review.

The real manager portal also supports physical table registration and a draft
offline table-QR print studio: manager/admin can produce A4 signage and
download vector SVG for permanent `/t/{publicAlias}` destinations. Staff
verification and temporary guest join proof remain necessary to place an order.
This capability is not part of the client-only portfolio tour.

These workflows run under disposable Docker/Chromium integration tests. QRIS/virtual accounts have **synthetic, explicitly non-payable** sandbox attempts only. No real bank settlement or WhatsApp/SMS receipt sender exists.

## Two ways to explore

### 1. Public-friendly portfolio tour

The **/showcase** route is a standalone, client-only interactive illustration of the customer-to-cashier-to-kitchen-to-waiter lifecycle. It requires no login, account, container, external API or database. The state resets when the page reloads, and the receipt is explicitly labeled **NOT A FISCAL RECEIPT**. It does not store personal data, open real table visits or touch real money.

The landing page at **/** introduces the product without exposing infrastructure diagnostics or claiming a public production service. Its illustrations are composed in CSS; the sample order in the hero is decorative, not live transaction data.

### 2. Full isolated application

The actual operational app requires PostgreSQL, Spring Boot, Next.js and authorized synthetic staff sessions. It is tested through Docker Compose in GitHub Actions. Start only on a disposable, local network with unique environment secrets; see [local bootstrap](M2-LOCAL-BOOTSTRAP.md), [deployment](DEPLOYMENT.md) and [security](SECURITY.md). Never reuse browser test fixtures or bootstrap profiles against a real database.

## Screenshots for portfolio

The GitHub Actions full browser acceptance job captures these **synthetic-only** images whenever the job succeeds and retains them for seven days as the artifact **portfolio-showcase-screenshots**:

- `portfolio-home-desktop.png`
- `portfolio-home-mobile.png`
- `portfolio-demo-guest.png`
- `portfolio-demo-kitchen.png`
- `portfolio-demo-receipt.png`
- `operational-table-qr.png` (real manager-only QR studio using synthetic data)

To get the images: open the CI workflow run for the portfolio PR, locate its Artifacts section, and download the above artifact. Most screenshots show the portfolio tour. The operational QR screenshot is from a role-authenticated, disposable PostgreSQL-backed table registry. None proves real payment-gateway settlement. Capture separate role-authenticated operational screenshots if the final case study needs OCC/KDS/Waiter views, using disposable test fixtures only.

## What was intentionally excluded

- Real merchant QRIS/VA payments, signed merchant callbacks and bank reconciliation.
- Phone/WhatsApp/SMS receipt delivery or collection of real phone numbers.
- Live public/staging deployment, production secrets, hosted backup/PITR, support and incident response.
- Production-signed Docker provenance, branch-protected release and human financial/security approvals.
- Automated persistent demo account provisioning or public writable demo database.

The full code is still in the stacked draft integration candidate PR #61; `main` remains a documentation baseline. This **does not block a factual portfolio write-up**, but a public live full-operations deployment or production-ready claim must wait for explicit release engineering, isolation, operator review and repository integration.

## Suggested portfolio summary

**Smart Cafe System** is a full-stack QR/NFC cafe ordering and operations platform built with Next.js, Spring Boot and PostgreSQL. It connects guest ordering, cashier approvals, kitchen preparation, waiter fulfillment, physical-game loans and audited cash settlement. A dedicated no-backend interactive showcase demonstrates the product flow; the core app is validated through containerized end-to-end tests with synthetic data.

**Status label:** Functional MVP · Portfolio showcase · Not production deployed.

## M7 guest discovery improvement (draft source)
The real guest menu now offers category chips and instant search across
published available products (including product descriptions) with a
clear no-results state. The search is client-only presentation over the
API-verified menu; server control of stock and price remains unchanged.
The existing real guest cash lifecycle browser test captures a narrow
mobile screenshot for reviewed portfolio evidence. This still requires
source CI and human integration review before claiming release.
