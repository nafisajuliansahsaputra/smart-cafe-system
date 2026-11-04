# M2 Durable Outbox Live Relay (Draft PR #12)

**Stage:** durable backend event publication. Frontend PR #11 still uses authenticated 5-second snapshot polling. This PR does **not** yet replace that SSE consumer with PG LISTEN delivery and must not be called a production-ready real-time dispatcher.

## Source of truth and schema

Existing checkout, cashier decisions, kitchen preparation/readiness, handoff and tender all write `outbox_events` **inside the same database transaction** as the domain change. V11 adds `live_published_at` as a separate per-consumer marker and `live_order_signals` with a unique `outbox_id`, monotonic `sequence_id`, order ID and event type. It stores **no guest name, phone, token, amount, cart body or payment credentials**. No API or public database access is granted to the new table.

The original `outbox_events.processed_at` is intentionally **untouched**. Publishing a UI signal must not falsely acknowledge the future receipt worker, payment provider or external connector.

## Transactionally consistent relay

`OutboxLiveRelay.dispatchBatch()`:
1. Obtains a global `pg_try_advisory_xact_lock(712, 2026)`, serializing **publication transactions** across multiple backend replicas. If held by another instance, it safely returns without reading/writing.
2. Reads up to 100 pending ORDER events in creation order with row locks and `SKIP LOCKED`.
3. Inserts a durable live signal with `ON CONFLICT (outbox_id) DO NOTHING` and marks the matching outbox row as published in the **same transaction**.
4. Calls `pg_notify('scs_order_live', order_uuid)` **only for newly inserted** signals. PostgreSQL publishes NOTIFY only on commit. The UUID is an internal wake-up hint; no customer data or financial amount is emitted. A listener must fetch its authorized cursor from the durable store instead of trusting that NOTIFY is durable.
5. Commits all rows together. On an error the marker, signal and pending NOTIFY all roll back. A crashed worker leaves events pending and can safely retry. Each signal row is unique per outbox event, including manual replay/reset.

The worker wakes once per second by default, configured with `SCS_LIVE_DISPATCH_ENABLED` (false unless explicitly enabled; Docker Compose sets true) and `SCS_LIVE_DISPATCH_DELAY_MS` (default 1000). Existing independent Java/Testcontainers tests are deterministic because the scheduler is disabled there. Do not set unrealistic millisecond intervals.

## Testing and safety

Integration tests use isolated PostgreSQL 17 and a synthetic staff/visit/order, verify two events publish exactly once, ordered sequence IDs, re-dispatch is a no-op, a forced replay cannot duplicate the durable row, and the unrelated `processed_at` field remains NULL. A foreign-key-invalid event fails the batch atomically and **cannot be silently acknowledged**.

**Important operational limitation:** a permanently malformed outbox event may block subsequent batches. The relay purposely fails closed rather than silently deleting evidence. Production needs a monitored retry/backoff/dead-letter workflow with human investigation before this can handle real revenue. No retention/pruning policy has been approved; live signals grow until a future safe retention/consumer-cursor protocol is implemented. PostgreSQL NOTIFY is not a durable queue. Monitor backlog age, pending count, relay errors, DB transaction time, signal retention and queue saturation. Never delete ledger events or prune without preserving late-subscriber recovery guarantees.

## Next slice

Add a properly scoped **live cursor / notification subscriber** (guest order ID ownership; staff role and waiter visit ownership), connection caps and reconnect replay. Then replace expensive five-second full snapshots with protected event-version checks or push while retaining a periodic re-auth/heartbeat. Apply load/soak tests before internet exposure.

No changes to PAID semantics, cash handling, QRIS/VA, real phone receipts, internet deployment or main branch.
