# M2: Live cursor cancellation and offline reconnect (Draft PR #18)

**Status:** stacked draft on PR #17. No real payments, public deployment, or merge.

## Problem and change

PR #16 introduced Spring MVC asynchronous `Callable` handlers, which free the Servlet worker while durable PostgreSQL cursor queries wait up to 14 seconds. A `Callable` does not ensure its underlying worker is interrupted whenever the HTTP exchange ends unexpectedly. Without explicit cleanup, abandoned virtual-thread waits can retain a *per-identity and global admission permit* until their normal expiry.

The new `LiveCursorAsyncBridge` returns Spring MVC `DeferredResult<ApiEnvelope<Cursor>>` for authenticated `?after=` endpoints. A dedicated virtual thread performs the **existing** role- and ownership-checked cursor wait. Servlet lifecycle callbacks (onCompletion/onError/onTimeout) request interruption and cancellation exactly once. The service's existing try-with-resources permit closes on interruption or completion, returning capacity to other devices. An interrupted/abandoned result never becomes a fabricated paid/order success. Initial cursor requests without `after` stay synchronous. The 20s Servlet timeout, 14s long-poll deadline, periodic reauthorization, and 48-global/2-guest/6-staff default admission constraints remain unchanged.

**Important limitation:** a passive TCP disconnect behind a proxy is not always reported to the servlet container while no bytes are being written. The hooks cancel promptly **when the container reports** the disconnect/error/timeout; otherwise the existing **14-second** normal wait bound still releases permits. Do not claim instant release for every network failure, or unlimited production capacity.

## Browser reconnect

The existing Next.js SSE remains same-origin, change-only, HttpOnly-cookie authenticated and sends no cursor, token, order body or PII. The React `LiveRefresh` component now closes the EventSource when offline/hidden, reconnects and re-reads server-rendered state on online/visible, and caps retry delays after SSE failures using 1, 2, 4, 8, 16 and 30-second backoff rather than an unchecked retry storm. Authentication-expired events stop automatic reconnect and refresh the protected page. Changes in a rapid burst are still coalesced with a guaranteed trailing refresh.

When the network returns, the page shows the **latest authorized state**; this is state resynchronization, not a guarantee to replay every event or an offline payment queue. A disconnected customer never creates a local PAID state. The waiter/cashier/kitchen API is authoritative throughout.

## Verification

- `LiveCursorAsyncBridgeTest` exercises cancellation before task start, interrupt-on-cancel, duplicate cancellation, suppression of abandoned results, and release/reacquisition of both admission permits.
- Existing `CashOrderFlowIntegrationTest` checks actual Spring MockMvc async dispatch with valid bearer and all ownership/role restrictions.
- Real Chromium synthetic cash lifecycle takes the **kitchen context offline before cashier approves**, sees reconnecting status, returns online, and asserts LIVE + ACCEPTED without navigation or manual refresh. Both ordinary localhost HTTP and local HTTPS-edge suites repeat the flow.
- Java/PostgreSQL/Flyway, Next.js lint/typecheck/build, clean-start Compose, isolated concurrency benchmark, and independent two-replica restart recovery CI are unchanged and must remain green.

## Remaining launch gates

Real-world CDN TCP-half-open disconnect testing, multi-hour soak, validated per-instance budgets, distributed ingress admission, alerting/metrics export, poison-event quarantine, authenticated admin/inventory mutations and external payment-provider sandbox remain future work. Neither abrupt container crash nor multi-AZ failover is certified. No customer PII or real funds are used.
