# M2 table management browser (draft PR #6)

**Branch:** `feat/m2-table-management-browser`, stacked on PR #5 → #4 → #3 → #2 → #1. No merge to main; do not enable real cafe traffic.

## Workflow implemented

1. An authorized WAITER/CASHIER/MANAGER/ADMIN opens `/staff/tables` from the staff homepage. The list shows all configured tables, occupancy and capacity, plus the public QR/NFC landing path. It never contains a join proof.
2. Available tables can be opened after physical-table confirmation. Spring Boot enforces the unique active visit and only opens AVAILABLE tables. The staff form renders the newly issued 43-character proof as a **one-time privileged action result** with its expiration timestamp, not a URL or persistent client store.
3. When a visit is active, only its opening waiter or a supervisor can rotate the join proof. Rotation invalidates older proofs immediately; active guest sessions are not revoked just by rotation.
4. Once all orders have reached settled/terminal states, the owning waiter or supervisor may close the visit after confirmation. Spring Boot audits the closure, denies unresolved order/payment combinations with 409, and revokes that visit's guest sessions.
5. Unrelated waiters can see that a table is occupied, but cannot access the visit UUID, proof expiry or any new proof. KITCHEN and anonymous callers cannot access the staff table endpoint.

## Security and implementation notes

- `GET /api/v1/waiter/tables` is a **no-store**, bearer-authenticated, read-only projection capped at 250 records. Only the active visit's owner or supervisor receives `manageableVisitId` and `proofExpiresAt`.
- Existing staff cookie is path-scoped, HttpOnly, SameSite=Strict, Secure in production. Each action re-verifies staff identity, then forwards the token only to the fixed internal backend route.
- Join proof is returned to React action state only after authorized open/rotate; no proof in list responses, static QR, query string, browser storage, analytics, or backend logs. Do not screenshot/share publicly.
- No backend changes to money, receipt or kitchen state. Opening a visit is not proof that guests have joined. Frontend approval/confirmation never bypasses database policies.
- Web uses server-authoritative refresh after actions. A timeout can mean the backend operation succeeded without its response; always refresh before repeating.

## Verification ledger

- New PostgreSQL integration test confirms anonymous and kitchen denial, table listing for staff, scoped active visit visibility, proof non-disclosure, no-store and closure behavior.
- Compose smoke adds anonymous denial for `/staff/tables` and `GET /api/v1/waiter/tables`. Final-head CI must pass before merge.
- **Still missing:** authenticated browser E2E for complete waiter → guest → OCC → kitchen → delivery → paid → receipt → close flow, real HTTPS/proxy/Origin/CSRF tests, guest join abuse throttling, and mobile/touch/a11y QA.

## Rollback

This PR adds no migration, affects no existing source of payment truth, and can be closed without changing prior cash state. Live records must never be reset through demo routines.
