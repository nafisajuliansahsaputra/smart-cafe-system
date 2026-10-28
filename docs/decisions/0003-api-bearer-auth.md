# ADR 0003: Server-revocable API bearer sessions for M2 backend slice

**Status:** Accepted for development implementation, subject to browser/pilot security review
**Date:** 2026-10-29
**Scope:** M2 one-cafe backend only

## Context

M1's Spring Security denied all business routes. M2 needs staff role controls and guest authorization scoped to one live table visit, while visual and browser architecture remain intentionally open for the owner to design. Client-side role fields, static table QR strings and permanent session tokens are forbidden.

## Decision

- Use independently random 256-bit opaque staff and guest bearer tokens issued server-side; only SHA-256 of each high-entropy token is stored in PostgreSQL.
- Staff tokens expire after 8 hours and are checked against current staff enabled/roles and revocation state on every request. Staff login checks bcrypt password and persistent account-oriented throttling.
- Guest tokens expire after 8 hours and additionally require current ACTIVE visit status; visit closure revokes them.
- Public QR/NFC alias is only a table locator. Staff-issued rotating join proof (10-minute TTL) authorizes guest join.
- Server does **not** accept ambient cookie credentials in this API phase. CSRF protection is not used for bearer-only endpoints; when browser session cookies or a Next.js BFF are implemented, require CSRF/origin defenses before enabling writes.
- No bearer token is persisted by the frontend in this slice. Do not implement localStorage token persistence as a shortcut.
- Only an explicit dev-bootstrap Spring profile may create a first local administrator, requiring owner-supplied secret environment variables. No default/staging/prod account is seeded.

## Consequences

Tokens are revocable and don't require a new microservice, Redis, or JWT signing key. The DB read cost per request is acceptable at the current single-cafe target; benchmark before any scaling changes. Cookie/BFF UI and phishing/XSS-resilient credential handling are still unresolved and require review. Rate limits by IP/network and durable event/incident monitoring remain production requirements.

The auth scheme is **not a claim of production readiness**. All financial actions remain server-authorized/audited; actual payment providers remain disabled.
