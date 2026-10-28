# M2 cash order domain guards (partial work)

**Status:** Domain-level implementation only, not a checkout or POS release.

The pure Java policy classes `CashOrderPolicy` and `CashTenderCalculator` start the M2 cash ordering vertical slice without inventing the unresolved cash collection location policy. They enforce:

- SUBMITTED + AWAITING_CASH can be approved to ACCEPTED.
- Approval changes order status only; payment remains AWAITING_CASH.
- Kitchen preparation requires accepted eligibility, then progresses PREPARING -> READY -> SERVED.
- An order can become SERVED while cash remains unpaid.
- Cash tender calculations reject insufficient funds, nonpositive dues and non-cash/paid states; all monetary values are integer IDR.
- A `CashTenderQuote` is **not** a payment receipt or authorization to update payment status.

**Not implemented:** staff login/roles, table join, DB-backed order/approval commands, immutable pricing, order idempotency, audit on staff actions, actual tender persistence, kitchen tickets, OCC API/UI, and receipt issuance. Those must be built and verified as one transaction-safe vertical slice. An approved/served cash order can only become PAID after tender evidence is committed with audit, never from these pure helpers alone.

No M2 checkbox is complete yet.
