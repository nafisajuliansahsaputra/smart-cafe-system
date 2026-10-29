# M2 Guest Join Throttling (Draft PR #7)

**Base:** PR #6, stacked on #5, #4, #3, #2, #1. No merge to main or public rollout.

## Why

The guest join endpoint accepts a 43-character temporary proof, and even incorrect proofs previously caused a database lookup. Attackers could repeatedly request joins or consume DB capacity. The static QR table alias is not a bearer credential; temporary proof verification remains mandatory.

## Implemented

- PostgreSQL Flyway V10 creates `guest_join_throttles`, unique HMAC-SHA256 bucket keys, fixed 10-minute rolling reset at window boundaries, atomic `INSERT...ON CONFLICT DO UPDATE...RETURNING` counts, and a cleanup index.
- `GuestJoinThrottle.allow(alias, socketPeer)` executes as a **separate transaction** before `TableVisitService.join`. Rejected/invalid join proofs therefore still increment and commit counters; throw 429 from controller afterward, not inside the throttle transaction.
- Three budgets per 10-minute fixed window: 1,200 requests per backend peer, 60 per table alias, and 24 per peer+table. All well-formed HTTP JSON join requests passing validation consume quotas, including successful joins.
- Fingerprints are HMAC-SHA256 of scope + value, never raw IP addresses or aliases. A mandatory `SCS_JOIN_THROTTLE_SECRET` (min 32 UTF-8 bytes) must be configured for API startup. Compose requires it; integration and CI use synthetic values. Do not reuse a secret in public deployments.
- `429 RATE_LIMITED` includes safe body, `Retry-After: 600` and `Cache-Control: no-store`. UI displays an actionable Indonesian retry message, without revealing internal counters, source identity or proof.
- Expired throttle rows are cleaned after 48 hours, hourly.

## Verified by integration tests

The added synthetic PostgreSQL/MockMvc test checks 24 failed proof attempts cannot roll back the counters; the next attempt gets 429 and no guest session. Forged `X-Forwarded-For` headers cannot bypass the guard. A different backend socket peer can join that active table, while the previous peer can use another table. Existing tests for valid QR proof, rotation, closure, payment and staff authorization remain intact.

## Explicit limits / release blockers

- **Important:** customer Next.js Server Actions forward joins to the backend; Spring Boot may see the **shared Next.js host socket IP**, not individual visitor IPs. The 24 peer+table allowance may therefore be shared by an entire table during a 10-minute interval. Avoid claiming independent per-visitor IP protection.
- Never trust user-provided X-Forwarded-For. A future trusted ingress/CDN must enforce per-client request budgets, known proxy IPs, request-size caps and malformed-body rate limits. This work remains open for a real public deployment.
- Fixed-window counters can briefly burst at boundary; gateway protection is needed to address this. HTTP Retry-After is deliberately conservative up to 10 minutes.
- Fail-closed DB outage makes legitimate joining unavailable; backend uses no in-memory bypass.
- No customer PII, real funds, real QRIS/VA or public demo access. Keep this draft until full authenticated browser E2E, security review and HTTPS reverse-proxy verification are complete.
