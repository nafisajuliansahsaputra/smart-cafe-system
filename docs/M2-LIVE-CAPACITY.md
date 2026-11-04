# M2 live long-poll capacity and observability (Draft PR #15)

**Status:** development safety slice stacked on PR #14. Not a production capacity certification.

## Scope and limits

The durable ORDER and TABLE_VISIT event pipeline, business authorization, cash/receipt truth and browser UI are unchanged. Previous cursor waits had only a single 48-per-API-instance semaphore. One guest/session could occupy all slots, causing a denial of live updates for other people.

`LivePollCapacity` now applies **both** an aggregate budget and an identity budget for **long-poll requests with `after`**, not for immediate cursor reads:
- `SCS_LIVE_MAX_LONG_POLLS` default **48**, allowed 1..128.
- `SCS_LIVE_MAX_GUEST_POLLS` default **2**, must be 1..global.
- `SCS_LIVE_MAX_STAFF_POLLS` default **6**, must be 1..global.

These are per-backend-instance limits. The identity key is `(GUEST or STAFF, internal UUID)`, never a bearer value. Admission uses an atomic concurrent map update plus a fair global semaphore; identity map entries are removed after the last permit closes. A permit releases exactly once on success, exception, interruption or timeout. Exhaustion returns the existing **HTTP 429 RATE_LIMITED** without subscribing or modifying orders/payments, and the existing browser maintains its manual refresh fallback. Input configuration fails startup for nonpositive, oversized or contradictory limits.

**These values are conservative initial configuration, not measured safe throughput.** Six staff subscriptions support typical OCC + table + order detail views. Multiple independent guest sessions on the same physical device are distinct identities, so ingress anti-abuse enforcement is still necessary.

## Aggregated Micrometer instrumentation

Without customer identifiers, token tags, order numbers, request paths, or money, instrumentation exposes:
- `scs.live.poll.active` gauge and `scs.live.poll.capacity` gauge.
- `scs.live.poll.accepted` and `scs.live.poll.completed` counters.
- `scs.live.poll.rejected{reason=global|per_identity}` counters, with only a fixed allowlisted reason.
- `scs.live.poll.occupied` Timer (permit occupancy).
- `scs.live.listener.connected` (0/1) gauge and `scs.live.listener.failures` counter.
- `scs.live.outbox.events.published`, `scs.live.outbox.dispatch.failures`, `scs.live.outbox.sample.failures` counters.
- `scs.live.outbox.pending` and `scs.live.outbox.oldest.seconds` gauges from read-only PostgreSQL aggregate query every **30 seconds** when enabled. On sample error both gauges become **-1**, meaning **unknown**, rather than falsely reporting a healthy/stale zero.

`SCS_LIVE_TELEMETRY_ENABLED` defaults false for library/tests and true in the disposable local Compose; `SCS_LIVE_TELEMETRY_INTERVAL_MS` defaults 30000. PostgreSQL LISTEN has an explicit connectivity gauge, so a disconnected listener is no longer a silent success.

**Metrics are registered internally in Micrometer.** Production metrics ingestion, authenticated exposure, retention, dashboard thresholds and alerts are NOT installed or enabled by this PR. The repository's Actuator HTTP exposure remains **health only**. Do not publish metrics or Actuator endpoints anonymously.

## Verification and limitations

JUnit concurrency tests race 20 virtual-thread callers against the global/per-identity limits and assert release/no leaks; they also check idempotent close and that one guest cannot block another guest while capacity remains. Tests validate invalid configuration, metering, disabled telemetry, failed PostgreSQL sampling marked unknown, and actual PostgreSQL outbox backlog counts before and after dispatch. The existing HTTP and HTTPS Chromium cash + secure live table/browser suites remain unchanged and are required to pass.

**Not yet implemented:** nonblocking Servlet async processing or separate async request executor, distributed rate limiting across API replicas, capacity and latency SLAs, metrics exporter/alert rules, backpressure/retry jitter tuning, soak/load tests under hundreds of devices, outbox poison-event quarantine and retention. This patch does **not** prove that 48 live polls are safe for a production Tomcat/DB pool configuration. Do not deploy to real customers or merge without review.
