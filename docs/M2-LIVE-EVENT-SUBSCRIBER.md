# M2 scoped durable live subscriber (draft PR #13)

The repository's transactionally published ORDER outbox (PR #12) is now connected to staff and guest browser SSE endpoints. **Scope:** ORDER lifecycle and receipt readiness. Table visit opening/closure continues using the existing authenticated snapshot watcher, because no table-specific outbox signal is yet issued.

## Safe transport path

1. Domain transaction commits an ORDER event to `outbox_events`.
2. The opt-in relay stores a unique `live_order_signals` row and commits a PostgreSQL `NOTIFY scs_order_live` hint.
3. One dedicated PostgreSQL LISTEN connection per Spring Boot process receives notifications. It only wakes an in-process monitor, not the browser. Lost NOTIFY packets are harmless: clients also recheck the durable cursor at most every 2.5 seconds while waiting.
4. New backend role-gated cursor endpoints do **not** return an event stream of orders. They return only the newest durable signal sequence visible to the authenticated principal. Queries constrain guest ID/visit ID or waiter-owned visit. OCC, kitchen and supervisor projections match the existing operational roles. Missing/foreign guest-owned order → 404.
5. Each client SSE connection stays entirely on the **same-origin Next.js BFF**. Next.js reads the HttpOnly browser cookie, injects the secret Bearer header to a fixed internal backend route, and long-polls the scoped cursor. On change it emits only `event: changed; data: {}`, not the cursor, token, order, money value or personal details. The existing React `router.refresh()` re-reads backend-authorized server components.

## Long-poll bounds and resync

- Spring Boot limits **simultaneous long polls to 48 per API instance**, returning 429 on exhaustion. Initial cursor calls are quick; long polls are capped at ~14 seconds. A single API listener uses one Hikari connection, not one connection per guest.
- The 14-second long poll wakes on NOTIFY and checks a scoped cursor; it also rechecks after at most 2.5 seconds to recover lost NOTIFY events or a listener restart. Each waiting request **re-authenticates** staff/guest tokens; revoked sessions close with 401.
- Next.js caps upstream fetch at 20.5 seconds and SSE at 55 seconds. EventSource automatically reconnects; each newly opened stream performs a fresh **authorized initial cursor read** and emits `ready`, prompting a server component refresh. This ensures missed changes reappear in current state, without claiming every individual event is replayed to the browser.
- LISTEN is enabled in the disposable Docker Compose environment through `SCS_LIVE_LISTEN_ENABLED`, off by default in unrelated backend tests. If listener unavailable, durable cursor polling remains correct.
- A reconnect may see coalesced changes. UI reflects **latest verified state**, never promises a lossless per-event UI queue. No payment/state mutation originates from the SSE route.
- The separate existing table management watcher stays on authenticated snapshots until table-visit event publication is implemented.

## Tests and limitations

PostgreSQL/MockMvc integration tests check that a guest can read only their own order cursor, foreign orders 404, staff/kitchen/ops role gates apply, waiter cursor excludes visits they do not own, and after a missed notification the durable cursor is still discoverable after relay. Existing HTTPS and HTTP Chromium acceptance verifies cashier → KDS → waiter → paid-only guest receipt without clicking refresh.

**Not public-ready:** PostgreSQL cursor polls remain short read queries every ≤2.5 seconds **while subscribed**; scaling hundreds of long-poll connections requires a properly sized async HTTP executor/connection budget, metrics, load and soak tests. Spring MVC currently uses a blocking request for the bounded long poll; 48 permits do not themselves constitute an optimal asynchronous worker pool. More production capacity work is required. A permanently malformed outbox row still needs monitoring and quarantine. Do not publicly expose this local HTTPS demo or use live funds.
