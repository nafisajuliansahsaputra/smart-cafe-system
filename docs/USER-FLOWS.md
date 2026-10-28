# User Flows and Screen Inventory

**Status:** Functional flows, not visual mockups. Actor and state behavior come from PRD and STATE-MACHINES. UI details can be designed separately while preserving API contracts.

## 1. Customer: arrival to receipt

C1. Customer arrives at table -> waiter starts active table visit and provides short join proof.

C2. Customer scans permanent printed QR / taps NFC URL -> sees table number and safe visit eligibility. Invalid/closed visit shows recovery instruction to contact staff.

C3. Guest joins: nickname required; receipt phone optional; opt-in/channel optional, separate from ordering. Stores scoped guest credential.

C4. Menu: categories, item detail, variants, allergen info when available, add/remove cart, clear totals and availability.

C5. Game catalog: optional game request for waiter-delivered physical board/card game, free in first version, availability is estimated until staff allocates copy.

C6. Review: item snapshots, quantities, taxes/fees and total before pay; clearly distinguish food from game loan.

C7a. Digital: choose QRIS/VA -> payment instructions and countdown -> verified payment confirmation -> eligible order admitted -> live status -> finalized receipt.

C7b. Cash: choose cash -> request approval -> staff verifies -> order accepted and kitchen prepares while still unpaid -> waiter serves and takes payment -> cash tender recorded -> receipt.

C8. Additional order: while visit active, customer opens menu and creates independent new order/payment. Prior receipt remains unchanged.

C9. Borrowed game delivered by waiter and logged. Customer may ask staff for return. Visit closes only after game returns and payments resolved.

## 2. Order Control Center: cashier/manager

OCC homepage:
- Live queue with clear priority: needs cash approval, provider pending, awaiting preparation, ready for delivery, payment exceptions, active game issues.
- List grouped by table/time, text search by order ID, filters by status, method, payment and assignee.
- Order drawer/detail with timeline, amount snapshots, payment attempts, fulfillment status, staff history and allowed buttons.
- Actions require permission and confirmation; financial overrides request reason.
- Staff can distinguish approved/unpaid cash from PAID digital at a glance.
- Reconnect to server and refresh authoritative snapshot after SSE connection loss.

OCC operational example:
1. Incoming cash order Table 07 Natsx -> staff verifies active visit -> Approve.
2. KDS receives order. OCC displays PREPARING + AWAITING_CASH.
3. KDS READY -> waiter task -> SERVED.
4. Waiter takes cash -> records tender and change -> PAID.
5. OCC shows settled; guest receipt becomes available; messaging job optionally dispatched.

Failure example: online payment success but kitchen out-of-stock -> OCC exception; staff resolves by replacement, refund/adjustment, or manager action. No automatic false fulfillment.

## 3. Waiter

- Dashboard shows active tables and open/close controls.
- Confirm valid cash orders; track delivery/collection tasks.
- Carry requested physical game copy, record handover and return condition.
- Mark food actually served and cash actually collected separately.
- Cannot silently delete paid order or change price.

## 4. Kitchen

- KDS displays only eligible accepted food tickets with creation/priority timestamp, items/variants and notes.
- Start PREPARING, mark READY; waiter informed.
- No payments, guest phone, role management or refund controls.
- Recover after disconnect by refreshing server ticket snapshot.

## 5. Admin

- Manage catalog/images/variants/prices and availability.
- Manage table QR alias, staff roles and activation of visits.
- Manage physical games and individual copies, damages and circulation.
- See audit and reporting; permissioned exceptions and reconciliation.
- Default app user locale Indonesian; design for English expansion later, but do not translate internal stable status enum keys.

## 6. Screen inventory

Customer:
1. Table Landing / Join
2. Guest Identity + Optional Receipt Phone/Consent
3. Menu Overview
4. Product Detail / Variants
5. Physical Games
6. Cart / Review
7. Payment Method
8. QRIS/VA Instructions / Cash Approval Waiting
9. Order Tracking
10. Finalized Receipt

Staff:
11. Staff Login
12. **Order Control Center Dashboard**
13. Order Detail / Exception
14. Waiter Table Board
15. Waiter Task/Delivery
16. Cash Tender Capture
17. KDS Queue
18. Game Loan Handover/Return
19. Admin Catalog
20. Admin Tables / QR
21. Admin Game Copies
22. Admin Staff / Roles
23. Reporting / Audit

## 7. UX quality

- Customer: responsive mobile-first, minimal steps, sticky cart/checkout, clear status and accessible labels.
- Staff: information density with legible contrast, large touch targets, batch filters and recovery.
- Clear pending/failure/confirmation visual differentiation, never create visually fake paid state.
- All screens support no data, slow network, server failure, expired token and permission-denied states.
- Language/copy must be clear on whether game is a request vs confirmed copy loan.
