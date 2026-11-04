# M2 Kitchen and Waiter browser slice

**Branch:** `feat/m2-kitchen-waiter-browser`, stacked on PR #3, PR #2 and PR #1. **Unmerged draft, development only.**

## What this implements

- Kitchen `/staff/kitchen` loads existing role-guarded KDS tickets from Spring Boot and displays order quantity, immutable modifier labels and safe preparation notes. Start and ready actions require explicit staff confirmation.
- Waiter `/staff/waiter` loads a new backend waiter-task projection of orders awaiting handover and cash settlement. The projection does not include guest phone, guest credentials or personal receipt links.
- Waiter deliveries call the existing auditable transition to `SERVED`. The cash form only appears after service, requires an affirmative physical-cash confirmation, and supplies an idempotency key per rendered form.
- Backend records `PAID` only after valid actual cash receipt; the server computes change. UI displays persisted received/change values when payment is settled, never browser-projected amounts.
- Cashier/manager/admin can supervise tasks; regular waiter only reads tasks belonging to visits they opened. Different waiter's tasks are absent from response. Kitchen cannot read waiter task details.
- Protected pages verify a live revocable staff session; backend repeats authorization on each command. Auth tokens never enter HTML or client JavaScript.

## API and state rules

`GET /api/v1/waiter/orders/tasks` returns at most 100 tasks with `READY`, `SERVED/AWAITING_CASH`, plus settled `SERVED/PAID` orders created in the past day. The user sees the backend's immutable order line snapshot and table code, not mutable prices.

The existing POST endpoints own all state transitions:
- `POST /api/v1/kitchen/tickets/{id}/start` requires kitchen role, accepted ticket.
- `POST /api/v1/kitchen/tickets/{id}/ready` requires kitchen role, preparing ticket.
- `POST /api/v1/waiter/orders/{id}/served` requires assigned waiter/supervisor, ready order.
- `POST /api/v1/ops/payments/{intentId}/cash-tenders` requires appropriate staff, served order, due amount covered and unique idempotency key; backend issues immutable receipt after paid.

## Verification and limits

- Java integration regression has been extended to assert waiter projection scoping (owner, unassigned, supervisor, kitchen and no token) and returned paid amount/change.
- Clean-start Compose smoke asserts unauthenticated kitchen/waiter pages redirect to staff login and unauthenticated task API denies.
- CI must pass on the **exact final PR head** before review; inspect run and failure logs.
- Authenticated full browser E2E, CSRF/proxy/HTTPS checks, real devices, accessibility and recovery after interrupted cash capture are still to be verified.
- No SSE yet, no automatic waiter assignment, no game loans, no QRIS/VA, no customer browser checkout, no live receipt sender, no merchant or tax policy approval.

## Manual acceptance (synthetic transactions only)

1. Start a clean local stack using the prior one-time test bootstrap, then restart without bootstrap variables. Create synthetic kitchen, waiter, cashier accounts via a controlled test fixture/procedure.
2. Without staff login, `/staff/kitchen` and `/staff/waiter` redirect; backend task API denies.
3. With kitchen token, see only admitted tickets. Start then ready transitions are verified by reloading; kitchen role cannot view `/staff/waiter`.
4. With assigned waiter, see the READY order. Another waiter does not see it; cashier sees supervisor scope. Confirm physical handover to mark SERVED, while payment stays AWAITING_CASH.
5. Submit a synthetic amount at least as large as order due. Confirm DB reports PAID exactly once, one receipt, one tender record and calculated change. Repeated different keys do not create an additional paid tender.
6. Disconnect API and confirm fail-closed view, no fabricated success. Reactivate and refresh from database.
7. Review no-cookie caching, redirects and backend negative permission checks on deployment origin.

## Rollback

PR #4 has no migration and can be reverted independently of stored orders, receipts or prior PRs. No existing financial records are overwritten by frontend updates. Retain prior M2 API contracts and use test-only reset procedures for synthetic data.
