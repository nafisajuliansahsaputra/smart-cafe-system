# ADR-0007: Free physical game loans with serialized unit allocation

- **Status:** Provisional, PR #19; not a production monetary policy.
- **Context:** The PRD requires physical, waitress-delivered board/card games. A game title can have multiple individually trackable copies. A visit must not close with unresolved loans.
- **Decision:** Use `physical_games` → `physical_game_copies` → `physical_game_loans`, with atomic visit locks, copy row locks, composite FK and database uniqueness to prevent simultaneous assignments. Guests request without reserving a copy; an authorized waiter assigns, physically verifies code on handover, and inspects return. A pending request also blocks table closure. Audit all staff transitions.
- **Financial policy:** v1 is strictly free. No deposit, rental charge, late/damage fee, checkout coupling, receipts or automatic lost-game penalty. Future fees require explicit product approval and separate idempotent payment/invoice design.
- **Known limitation:** Return confirmation means an inspected good return only; damaged/lost quarantine and recorded liability resolution require a separate approved workflow. No manager shortcut to erase an in-use loan.
- **Alternatives:** Treating stock as a quantity was rejected because it cannot identify or protect a physical copy under concurrency. Auto-delivering requested games was rejected as unsafe and operationally false. Reserving units before waiter verifies table was rejected for the initial slice.
- **Follow-up:** Safe game event outbox and scoped live subscriptions, damage/loss/repair workflows, operational alerts and UI tests with actual café staff.
