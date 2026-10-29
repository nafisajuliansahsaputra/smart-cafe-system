# M2 asynchronous long-poll and isolated concurrency gate

**Draft PR #16; stacked on PR #15.** No production deployment or authorized live customer traffic.

## Before and after

Before: `/api/v1/{me,ops,kitchen,waiter}/live-cursor?after=N` waited up to 14 seconds inside the Servlet request handling thread, potentially exhausting the HTTP executor long before the database was overloaded. The existing global and per-principal limits (48/2/6 by default) protected the number of *accepted* waits, not the scarce HTTP worker threads.

After: the **presence of `after`** selects a Spring MVC `Callable<ApiEnvelope<Cursor>>` endpoint. Spring MVC releases the Servlet request thread and executes the bounded cursor wait on the JDK 21 `VirtualThreadTaskExecutor`. Once complete it dispatches the response through standard MVC serialization and error handlers. Async timeout is 20 seconds, comfortably longer than the service's 14-second limit. A request **without** `after` remains synchronous with the same wire contract. No new dependency, public route, payment mutation or UI change.

Authorization is checked on the initial request by Spring Security. The worker receives an authenticated guest/staff principal and the bearer token captured **on the request thread**, not an arbitrary URL. `LiveCursorService` retains its strict topic/UUID validation, ownership/role filter, and periodic reauthentication while waiting. The existing `LivePollCapacity` bounded per-identity and global permits still apply, with `finally`-safe release on error/interrupt/timeout. The HTTP response stays the same `ApiEnvelope.data.cursor`; only server-side waiting changes.

**Important:** virtual threads reduce Servlet worker occupancy, but **do not remove database load or connection limits** and are not proof of unlimited scalability. A PostgreSQL LISTEN connection remains shared per API process. A 429 is still expected under admission pressure.

## CI proof and measurement

`CashOrderFlowIntegrationTest` now asserts that a pending cursor has MVC async processing enabled, the immediate cursor route still works while waiting, and the pending request later returns the valid authorized cursor via `asyncDispatch`. The prior guest ownership, per-role restrictions, no-PII SSE, state transitions and paid-only receipt tests must remain green.

A dedicated GitHub Actions **Async live-cursor / isolated concurrency benchmark** job:
1. Builds a fresh disposable Compose backend/PostgreSQL with a deliberately small 12-long-poll global budget, 3 per-staff and 2 per-guest (CI-specific settings, **not** a recommendation for production).
2. Seeds 16 synthetic cashier accounts using `infra/fixtures/e2e-live-load.sql`, never a real merchant or existing database.
3. Uses built-in Node 22 `fetch` in `infra/perf/live-cursor-load.mjs` to burst 36 simultaneous 14-second cursor requests across 12 staff accounts, expects **both successful waits and 429 admission rejections**, and requests Actuator health **during** the wait. It records wall duration, measured accepted/rejected counts, p50/p95 duration and concurrent health latency.
4. Sends another wave of requests from one staff identity that exceeds its 3-slot budget, alongside a second principal, expecting the first to be partially denied while the second continues.
5. Uploads only synthetic aggregate data to the Actions summary and destroys container volumes. No real customer or order body is needed.

Measurements vary by CI host, JVM warm-up, Docker startup and network timing. Use the exact-head run's observed numbers rather than inventing capacity. These are **regression smoke and saturation tests**, not true multi-hour soak tests or a proof for 100/500 devices. A future dedicated staging load suite needs connection/DB pool sizing, metrics export with authenticated access, p95 end-to-end UI lag, timeout/retry load and multi-replica fan-out.

## Known gaps and stop conditions

- Servlet async requests must be tested behind the actual trusted HTTPS proxy/CDN; short-lived self-signed localhost HTTPS test is not a production configuration.
- Client disconnect cancellation and HTTP intermediary timeouts may vary; service timeouts bound the worst-case, but a production cancellation/abandonment test is still required.
- The 48 default permit cap remains per API process. A bot may obtain many visit credentials; ingress throttles, load balancing and distributed admission remain necessary.
- Backpressure, unknown/poison outbox rows, retention, headroom alarms, disaster recovery, admin inventory, physical-game loans and real payment services remain outside this PR.
- No merge to main or public deployment.
