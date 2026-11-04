# M2 Table visit lifecycle durable events (Draft PR #14)

## Objective and scope

Table management no longer needs full table snapshot polling to detect successful **open**, **rotate join proof**, or **close** operations. Each completed mutation emits a minimal `TABLE_VISIT` event to `outbox_events` in the **same PostgreSQL transaction** as the table visit and audit log. A rejected or rolled-back operation emits no event. The existing guest join proof never appears in any event, `payload_safe` or live notification.

Flyway V12 adds `live_table_signals` with unique `outbox_id`, visit and table foreign keys, sequence identity, event type and timestamp; it has no token, join proof, guest display name, phone or money. PR #12's global transaction-locked relay now handles both ORDER and TABLE_VISIT, with idempotent publication per outbox ID and a commit-only PostgreSQL NOTIFY wake-up hint. Business `processed_at` remains untouched.

## Scoped read access

`GET /api/v1/waiter/live-cursor?topic=tables` uses the existing bearer-protected waiter/manager/cashier/admin route. The endpoint returns a numeric **event cursor only**. Any waiter can observe OPEN/CLOSED table occupancy signals (already visible through `GET /api/v1/waiter/tables`). A code ROTATION signal is visible **only** to the opening waiter and authorized supervisor, because only those roles can see the proof-expiry metadata. Kitchen and anonymous clients are rejected.

Next.js `/staff/live?topic=tables` now uses this durable cursor and existing same-origin, HttpOnly, Strict cookie BFF bridge. Browser SSE events still contain only empty `changed` signals, never event records, cursor, proof, PII or payment details. Server Components re-read the already role-filtered table projection. The backend's shared PG LISTEN and periodic cursor check recover missed NOTIFY events and coalesce changes after reconnect.

## Acceptance evidence

Java/Testcontainers verifies open/rotate/close publication, durability and the separate business outbox marker, plus denied kitchen access and non-owner rotation cursor isolation. Chromium acceptance opens a second cashier tab watching table availability and verifies occupied/open and available/closed updates without manual refresh, over both local HTTP and HTTPS proxy. The full guest cash, kitchen and paid-only receipt flow must continue to pass.

## Known remaining work

This does not cover administrative table inventory changes, game loans, NFC hardware, QRIS/VA or external receipt sending. The Spring MVC bounded cursor waits still consume worker capacity, so concurrency sizing, async execution, admission controls and production soak testing remain required. No public or production deployments or merging stacked draft PRs.
