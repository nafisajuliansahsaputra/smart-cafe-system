# SKILL.md — Smart Cafe System Engineering Playbook

This document is a repository-local contributor playbook inspired by existing NATSX repository documentation. It is **not** an installed plugin skill and does not supersede PRD or ARCHITECTURE.

## Mission

Build a reliable cafe ordering and operations product demonstrating enterprise Java backend, PostgreSQL transactions, real-time staff coordination, payment integration, game-loan inventory and DevOps.

## Before any task

1. Identify TODO milestone and actor (guest, waiter, kitchen, cashier, manager, worker).
2. Read relevant PRD journey.
3. Check state machine and DB invariants.
4. Identify trust boundary, permissions and negative cases.
5. Pick the minimum vertical slice and its test plan.
6. Preserve existing UI if change is not UI-specific.

## Backend recipe: Spring Boot domain slice

- Use domain/application/infrastructure interfaces with clear module boundaries.
- Express transitions as validated commands, not arbitrary setter calls.
- Use DTO request validation; never expose persistence entities to clients.
- Fail with stable typed error codes and structured logging correlation IDs.
- Use Flyway migrations before repositories; ensure constraints mirror business rules.
- Write JUnit tests for domain, Testcontainers integration for persistence and concurrency.
- Expose OpenAPI schema and explicit pagination.
- Use BigDecimal or integer minor units for money; no double.
- Avoid external HTTP inside a DB transaction.

## Table/session recipe

- Static table alias must not authorize checkout by itself.
- Require active visit and rotating staff-provided join proof.
- Issue short-lived scoped guest credential; revoke on closure.
- Support multiple guests per table with separate carts/order ownership.
- Test old QR/old session, wrong guest, closing while unsettled.

## Order/OCC recipe

- Persist immutable line/total snapshots.
- One checkout attempt idempotently maps to one order.
- OCC is authenticated and protected; cashier and manager must see full operational queue.
- Waiter cash approval grants kitchen eligibility but does not settle a payment.
- Kitchen actions cannot modify payment.
- Every privileged cancellation/adjustment creates audit.

## Payment recipe

- Separate order, payment intent, payment attempts and provider events.
- Verify gateway authenticity and business invariants before applying event.
- Use idempotent callbacks, pending/failed/late-success reconciliation.
- Maintain clear distinction between cash approval, cash collected, digital settled.
- A receipt is issued on a confirmed finalized sale, not on a click/redirect.

## Physical-game recipe

- Model game definition and physically serialized copies separately.
- Atomic reservation to prevent a copy being assigned twice.
- Waiter confirms handover and return, recording condition and timestamps.
- Reject ordinary closure with unreturned copy.
- Do not assume all games are free permanently; parameterize fees without implementing billing complexity early.

## Phone/receipt recipe

- Phone is optional; normalize E.164, validate and protect access.
- Consent for receipt channel must be purpose-specific, separate from marketing.
- Always offer on-screen receipt/download when payment is confirmed.
- External send uses asynchronous durable outbox with dedupe, retries and failure status.
- Prefer a short, expiring, unguessable link. Never embed phone or secrets in the URL.
- Provider choice/approval costs and regional messaging rules must be verified before production use.

## Frontend recipe

- Customer UI: mobile-first, concise scan-to-checkout, accessible fields and actionable errors.
- Staff OCC/KDS: quick scanability, filters, keyboard/touch, state freshness, safe confirmations.
- Never display PAID if server only knows PENDING; never optimistic-update critical financial actions as success.
- SSE updates should trigger re-fetch/read-model reconciliation; robust reconnect.

## DevOps recipe

- Local Docker Compose with PostgreSQL and fake providers first.
- Health/readiness endpoints and migrations from clean environment.
- CI build/tests/secret scan; deploy to staging after app exists.
- Observability for order submit, payment success/failure, backlog, game allocation and receipt job.
- Test restore and rollback before any live pilot.

## Definition of done

Requirements met, positive and negative tests pass, authorization checked, DB migration verified, no secrets/PII exposure, docs and TODO in sync, evidence of working feature available.

## Tooling constraints

No arbitrary unattended coding, main pushes, CI secret changes, customer messaging or real payment execution without owner authorization. No speculative dependency version pinning before checking current supported versions. If a capability is unavailable, record the limitation rather than simulate success.
