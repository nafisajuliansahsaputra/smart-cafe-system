# M2 Staff principal and Server Action boundary hardening

**Status:** Draft PR #9, based on PR #8. Synthetic development testing only, no public rollout.

## Issue addressed

Spring Security formerly used a generic `authenticated()` rule on the staff `GET /api/v1/staff/auth/me` and `POST /api/v1/staff/auth/logout` endpoints. **Existing behavior already returned 401 for a guest bearer**: `GuestBearerFilter` only authenticates guest API paths, not `/staff/auth/**`. The new explicit staff role requirement is defense-in-depth in case filter scopes change later. Regression tests confirm guest bearer 401 for both staff endpoints and continued access to its own visit.

## Next.js Server Action protection

- React/Next.js handles mutations through same-origin POST Server Actions. `next.config.ts` **does not configure additional allowedOrigins**; it relies on framework Origin/Host validation rather than accepting wildcard origins. A real proxy must not accept arbitrary `X-Forwarded-Host` or Forwarded values.
- Each Server Action request is limited to 32KB; no file upload is part of current operational or guest forms.
- `/staff` and `/visit` have no-store/referrer and frame-denial headers; public table route also gets no-referrer, frame-denial, no-sniff and conservative device-permissions headers.
- Real Chromium tests attempt cross-origin logout with staff cookies and check that the request fails and the active session survives. A distinct 64KB login form is rejected without minting a staff cookie. Normal login works afterward.
- The existing full cash flow test now checks unsettled table closure fails before cashier acceptance, then success after physical tender.

## What this does NOT prove

Localhost CI is not a deployed reverse-proxy/TLS/origin test. Correctly configure Host/X-Forwarded-Host at the trusted edge; do not add wildcard allowedOrigins; do not forward caller-controlled headers upstream. Real per-client throttle, HSTS/CSP, Cross-Site Request Forgery tests behind production proxy, many-device QA, customer PII/phone retention and cash settlement approval are still release blockers.

## Verification

The repository has **four** required jobs: Java/Flyway/PostgreSQL, Next.js lint/types/build, clean-start Compose smoke and Chromium authenticated/negative browser suite. Only treat PR #9 complete when the exact final commit reports all four green. Synthetic test accounts and DB are removed at end of run.
