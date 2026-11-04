# M2 authenticated Chromium browser acceptance

**PR #8** stacked on #7 → #6 → #5 → #4 → #3 → #2 → #1. Development-only E2E test, not a deployment approval.

## Setup and isolation

CI job `browser-e2e` runs on an isolated Ubuntu runner; `.env` is created with non-production Postgres and HMAC values, `docker compose up --build` starts Postgres 17/Spring Boot/Next.js, and `infra/fixtures/e2e-browser.sql` seeds a **disposable** table, demo menu, two modifier groups, and three distinct staff users (waiter/cashier/kitchen) with artificial test-only BCrypt credentials. The fixture never runs by default, is not loaded by Flyway, and must never be run against shared or live data.

Playwright uses a separate installation for CI rather than becoming a production dependency. The job installs Chromium, executes `apps/web/e2e/cash-cycle.spec.mjs`, uploads failure-only traces/screenshots, and always destroys Docker data volumes. Any test failure fails the job.

## End-to-end flow and negative assertions

1. Log into three isolated role-scoped browser contexts; unauthorized kitchen/waiter OCC and cashier kitchen pages return 404.
2. Waiter opens E2E-01 via `/staff/tables` after checkbox confirmation and receives a newly issued 43-character visit proof. Public QR landing has no secret.
3. Independent guest browser enters nickname + proof on `/t/e2e-01`. Guest token must have HttpOnly, Strict, `/visit` cookie attributes, and be absent from HTML, document.cookie and localStorage.
4. Guest chooses `E2E Latte`, required `Large` (+5,000) and optional `Oat Milk` (+2,000); cart subtotal is authoritative IDR 35,000 (base 28,000).
5. Guest confirms and submits cash checkout via Server Action. Receipt must not exist before payment and payment remains `AWAITING_CASH`.
6. Another valid guest joins that visit in an isolated browser and gets 404 trying to read the first guest's order.
7. Cashier approves the exact order, kitchen starts then readies the ticket, waiter confirms delivery and receives synthetic cash IDR 50,000. Backend records PAID, an immutable receipt and change IDR 15,000.
8. First guest sees the PAID state and issued receipt. Owning waiter closes the now-settled table. Guest browser loses authenticated visit access.

## CI verification boundaries

This suite uses the actual deployed application and browser clicks rather than mocking the backend. It complements the 34 Java/unit/Testcontainers tests. It does **not** test HTTPS/TLS termination, actual proxy headers, live payment or external receipt adapters, multi-browser/mobile accessibility, concurrency under public traffic, or protected origin CSRF policies. Guest per-client rate limiting and other production security gates remain open.

If the Chromium CI job fails, document the failing step and keep this PR in draft until fixed. Do not merge the stacked PR series or deploy actual cafe traffic just because CI goes green.
