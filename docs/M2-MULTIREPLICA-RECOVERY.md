# M2 two-replica live-event recovery acceptance (draft PR #17)

This slice adds a **disposable integration test**, not a deployed cluster or a guaranteed production failover SLA. No backend payment, guest identity, QR/NFC proof or public-domain deployment is changed.

## Architecture under test

The opt-in Compose overlay `infra/perf/compose.replicas.yaml` runs **two independent Spring Boot JVMs** with a shared disposable PostgreSQL 17 database. Both listeners receive committed PostgreSQL `NOTIFY scs_order_live` hints independently. Either instance may publish committed ORDER/TABLE_VISIT outbox events because the existing global PostgreSQL transaction advisory lock allows only one relay transaction at a time. The durable `live_table_signals` table, **not NOTIFY**, is the recovery source of truth.

The overlay binds the primary API on `127.0.0.1:8080` and second API on `127.0.0.1:8081`, does not create a public reverse proxy or additional exposed database, and disables no authentication checks. Both instances use only synthetic CI staff/table credentials. The second instance has an explicitly duplicated, minimal environment, not Compose `extends`, to avoid inheriting the primary host-port mapping.

## Automated acceptance

`infra/perf/multireplica-live-recovery.mjs`, invoked as a **seventh independent GitHub Actions job**:

1. Verify both API processes reach the same database and authenticate synthetic waiter/cashier accounts through normal bearer-login endpoints.
2. Read independently authorized table cursors from both processes and confirm initial consistency.
3. Hold a long-poll request on **each instance**, open a synthetic table visit using the normal waiter API, and require **both requests** to see the committed event. Measure per-process wakeup latency, but do not infer any production SLA.
4. Stop the second API process. Rotate the join proof on the first instance, with the second instance unable to receive the corresponding NOTIFY; wait for the durable relay to commit.
5. Restart the second API process. A fresh authorized cursor read **must see the previously missed event** without relying on an in-memory notification backlog.
6. Subscribe on the recovered instance again, close the empty synthetic visit from the first instance, and verify a subsequent change is detected.
7. Verify anonymous cursor access is denied and kitchen credentials cannot subscribe to waiter table cursors.

The test uses real HTTP to independent local JVMs, not two beans in one Testcontainers JVM. It does **not** assert that every individual event is delivered to browsers; SSE intentionally resynchronizes the latest authorized state. The network uses loopback and disposable identities only. CI tears down both containers and their ephemeral PostgreSQL volume even if a test fails.

## Operational interpretation

Successful acceptance shows that **a missed PostgreSQL notification does not permanently lose the durable cursor** after a backend process restart, for this tested scenario. It also provides evidence that committed table events can be consumed by two replicas.

### Not covered

- Production load balancer behavior, DNS or TLS certificate rotation, sticky-session policy, multi-AZ Postgres failover and failover during in-flight money operations.
- A genuine abrupt OS kill, client-side mid-stream network cancellation, burst load and multi-hour soak. Docker's graceful process stop/restart is a narrower failure mode.
- End-to-end customer UI reconnection over a real public CDN; browser acceptance through the separate local HTTPS proxy remains an independent job.
- Global cross-replica admission (current 48/2/6 caps remain **per instance**), poison-event quarantine, metrics export, alerts, retention policy or real-world p95 status latency.

Do not expose this overlay publicly. To re-run, use only a disposable local database and the dedicated CI job, not a real cafe environment.
