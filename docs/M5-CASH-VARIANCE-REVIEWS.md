# M5: Cash variance investigation (draft PR #58)

## Purpose

A manager/admin can document the follow-up to an **immutable closed drawer shift
with a nonzero discrepancy**, without changing cash, payment, receipt, order,
staff handoff, refund, bank balance, or the final shift snapshot. A discrepancy
is neither proof of theft nor confirmation of recovered cash.

The review actions are deliberately **not** `RESOLVED`, `PAID`, `REFUNDED`
or `WRITE_OFF`. They are internal process instructions only:

- `REQUEST_RECOUNT`: ask for an independent physical recount.
- `DOCUMENT_EVIDENCE`: record an internal explanation or evidence reference.
- `ESCALATE`: bring the case to a supervisor/owner for independent review.

No arbitrary money corrections, cash journal write-offs, manager overrides of
struk, or automated settlement approval are introduced.

## API and access

Manager/admin only at both UI and backend service layer:

- GET `/api/v1/ops/cash-variance-reviews?page=0`: at most 20 closed,
  nonzero-variance shift summaries per page (21 internal rows to derive
  `hasNext`, page range 0..1000); newest first, stable ID tie-break.
- GET `/api/v1/ops/cash-variance-reviews/{shiftId}/history`:
  newest 100 append-only notes for that discrepancy.
- POST `/api/v1/ops/cash-variance-reviews/{shiftId}/actions`:
  `{actionCode,note}` and required `Idempotency-Key` header,
  8–128 ASCII-safe characters. Note must be 10–300 printable characters.

Backend verifies shift is **already closed and discrepancy is nonzero**,
so clients cannot create cases on open or balanced shifts. The shift row
is locked in the transaction, so concurrent duplicate submissions in
multiple API replicas cannot double-insert. Exact same actor, action, note
and key returns the prior result. Reusing that key for different content
produces HTTP 409. New, explicit distinct actions are appended, never
overwrite prior notes. Read/writes are `Cache-Control: no-store`.

The staff UI is at `/staff/cash-shifts/investigations`, accessible only to
MANAGER and ADMIN; CASHIER/WAITER/KITCHEN do not get the page. Backend
enforces the same boundary independently and never includes guest PII.

## Persistence

Flyway V30 creates `cash_variance_review_actions` with a restricted action
vocabulary, staff/shift FKs, reason and key constraints, per-shift idempotency,
and an update/delete denial trigger. A second `audit_logs` entry records
each successful new review action in the same transaction. Shift snapshots
remain immutable; this is a distinct audit stream.

## Verification

The cash Testcontainers integration exercises manager-only reads, cashiers
and waiters denied, exact-key replay, conflicting key reuse, invalid actions,
no zero/open-shift reviews, immutable audit records, and that the original
cash difference and receipt count are not changed. Browser acceptance uses
the synthetic -1,000-IDR closed cash shift created in the guest-to-cash
journey, then manager records a recount request and reads the new history.
Existing security, Flyway, PostgreSQL, cash, digital sandbox isolation and
HTTP(S) browser workflows must pass on the exact PR HEAD.

## Outstanding controls

Physical recount SOP, operator-independent evidence verification, shortage
responsibility, return of cash, customer privacy redaction, management
sign-off and accounting/bank adjustments require explicit owner policy.
These case notes do not settle any of those questions. This remains a
staging-oriented single-cafe/single-till prototype, not production certified.
