# M2 authorized live status via SSE

**Draft PR #11**, stacked on #10. No deployment or real payments.

Browser EventSource connects to Next.js `/staff/live` or `/visit/live` through its existing path-scoped HttpOnly Strict session cookie. Route Handlers validate allowlisted topics and optional UUID, then request fixed Spring Boot projections with the authorized bearer. An unauthorized/foreign guest cannot begin a stream. Every five seconds, a fresh scoped backend snapshot is hashed; only changes generate SSE events with empty payloads. The browser uses `router.refresh()` to re-fetch authoritative server-rendered state. Session revocation closes the stream. No payment values, names, tokens or receipt bodies travel through SSE.

Routes cover OCC queue and detail, Kitchen Display, waiter tasks, table availability, guest own order list and detail. A guest's PAID receipt is fetched only through its existing backend payment/ownership check. Invisible browser tabs close SSE; visible tabs reconnect and re-sync. Streams terminate after 55 seconds; browser reconnects automatically. Existing manual refresh remains as a fallback during an outage.

**This is snapshot polling via SSE, not durable outbox push.** Each active stream creates a database-backed read about every five seconds. Production scaling requires admission controls, concurrent-connection caps, overload monitoring, load tests, a push dispatcher and repeatable staging soak tests. A real proxy and multi-device connectivity remain unverified. Browser E2E exercises updates across separate staff/guest contexts without navigation, plus denied foreign order streams. No live funds/PII or public deployment is authorized.
