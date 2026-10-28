# AGENTS.md — Smart Cafe System Contributor & Agent Contract

This file applies to humans, Codex, and all coding/automation agents working in this repository. **Writing docs does not authorize autonomous background jobs, deployment, production operations, or direct push without owner approval.**

## 1. Read-first order and authority

1. PRD.md — agreed product behavior and scope.
2. ARCHITECTURE.md — system boundaries and technical invariants.
3. docs/STATE-MACHINES.md — order, payment, game and table transitions.
4. docs/DATA-MODEL.md and docs/API-CONTRACT.md — database and interface contracts.
5. docs/SECURITY.md and docs/RECEIPTS.md — trust, personal data and integrations.
6. TODO.md — execution frontier and real status.
7. WORKFLOW.md and SKILL.md — how to implement/review.
8. Relevant code and latest ADRs.

When documents conflict, do not blindly pick the easiest. Follow PRD then architecture, document the inconsistency, and reconcile before an affected feature. Accepted later ADRs must be synchronized into canonical docs.

## 2. Locked product boundaries

- Name: **Smart Cafe System**; repository smart-cafe-system; no silent rename to NATSX CaféOS.
- One cafe first; a multi-tenant SaaS backend is NOT required for MVP.
- Customer does not need an account or app install.
- QR/NFC opens a table URL and does not grant perpetual ordering access.
- **Order Control Center (OCC) is mandatory**. Cashier/manager operate it; it is not a marketing mock or admin afterthought.
- Waiter, kitchen, cashier and admin duties remain distinct and permission-gated.
- Cash approved for cooking remains UNPAID/AWAITING_CASH until funds are physically received and recorded.
- QRIS/VA pending != paid; verified gateway truth only.
- Game is a **physical unit loan** via waiter, not a playable web game.
- Phone is optional, used for receipt delivery only with appropriate purpose/opt-in; checkout and receipt viewing work without it.
- Completion is recorded against TODO only after verified implementation and tests.

## 3. Engineering priorities

1. Financial correctness, fraud resistance and authorization.
2. Operational safety and inventory consistency.
3. Testability and recoverability.
4. Staff throughput and customer usability.
5. Performance based on measurements.
6. Visual polish.

Do not trade correctness for animations or a screenshot.

## 4. Mandatory backend rules

- Server validates identity, active table visit, ownership, permissions, totals and stock.
- Data access is scoped by table visit/guest/staff capabilities.
- Use DB transactions and constraints for concurrency; not only frontend locks.
- Use an idempotency key for every duplicate-prone write.
- All state changes go through explicit domain transitions.
- Kitchen status and payment status stay independent.
- Provider webhook verifies signature, identity, amount, merchant/environment, status semantics; records raw-safe event identity and deduplicates.
- Never call payment success solely from client redirect or user screenshot.
- All financial staff overrides use audit and reason.
- Game copy cannot be borrowed by two visits at once.
- External receipts are dispatched through durable jobs/outbox.
- No unbounded provider retry loops; timeouts, backoff, dead-letter/manual resolution.
- Never mutate a finalized receipt silently.

## 5. Frontend and design boundaries

- Customer UI mobile-first, fast, accessible, clear checkout and status.
- Staff OCC/KDS/waiter screens optimize at-a-glance state and error recovery.
- UI must display server-confirmed truth and distinguish pending from completed.
- Never fake success states or fabricate live provider payments.
- Preserve established UI/UX when a task targets backend only. Coordinate contract changes that affect UI.
- Error/loading/empty/expired-session states required.

## 6. Data security and privacy

- No API keys, test credentials, real phone numbers, signed receipt URLs, payment token, cookies or personal data in commits/issues/screenshots/logs.
- Phone numbers should be normalized, access-controlled, masked in ordinary UI and encrypted at rest where appropriate.
- Do not opt in customers to marketing merely because they request an order receipt.
- No provider integration before legal/contract and sandbox requirements are validated.
- Fail closed for unauthorized operations. Staff account disablement must revoke access.

## 7. Testing discipline

For each changed rule implement corresponding allow/deny/duplicate/error tests. Critical regression scenarios include:
- reused QR after table close;
- wrong-guest/wrong-table access;
- double checkout;
- last stock/game copy race;
- cash approved but not collected;
- duplicate/out-of-order payment webhook;
- gateway timeout after creating attempt;
- retry receipt without duplicate send;
- unauthorized refund/void;
- closure with unpaid order or borrowed game.

Do not mark a milestone done with only mock UI.

## 8. Definition of done

- Requirements + acceptance criteria satisfied.
- Code passes build, lint/format, unit and relevant integration/E2E tests.
- DB migration reproducible from a clean database.
- Negative/security and concurrency tests pass for sensitive work.
- Logs avoid secrets/PII; metrics or diagnostics for failure path exist.
- No unrelated UI drift or scope creep.
- PRD/architecture/contracts/TODO updated when changed.
- Document test evidence, known limitations and rollback impact.

## 9. Working permissions

- No background/autonomous scheduler configuration without explicit owner request.
- Do not deploy, rotate secrets, change live payment configuration, change production data, purchase SMS/WhatsApp messages, or merge outside specific authorization.
- Prefer focused branches and PR review. Documentation initial commit on main does not imply blanket main write authority.
- Do not silently add cloud vendor lock-in, new payment providers, microservices or dependencies.

## 10. If uncertain

State the uncertainty in an ADR or task, choose conservative reversible defaults, preserve security and user money, and avoid inventing policy. Use mock providers only in an explicitly labeled demo/sandbox environment.
