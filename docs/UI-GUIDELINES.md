# UI Guidelines — Smart Cafe System

**Status:** Functional UX baseline only. No UI mockups or visual theme are locked. Product owner can define visual identity independently without changing backend correctness.

## 1. Two interaction contexts

**Guest:** mobile-first, editorial but readable, high-quality food photography, simple categories and large tap targets. Order, game requests and payment must remain unambiguous regardless of animation.

**Staff:** functional information density, clear status priority, readable on tablet at kitchen/work area, keyboard/touch accessible, minimal motion, safe confirmations and actionable error states.

Use shared design tokens for space, typography, colors, elevation and motion. Avoid hard-coding theme colors inside domain components. Support contrast and focus.

## 2. Screen contracts

**Customer**
- Table landing shows table identity and join requirement.
- Guest join asks nickname; phone+send receipt preference optionally.
- Menu filters/category, quick item customization.
- Cart and review shows authoritative price/tax/fees and game requests separately.
- Payment shows distinct QRIS/VA instructions vs cash approval waiting.
- Tracking shows payment state AND preparation/serving state (not one ambiguous badge).
- Finalized receipt offered in-app/download and optional send status.

**Order Control Center**
- Must show orders by time, table, kitchen, payment and method with risk flags.
- Show a distinct priority queue for cash approval, pending digital, ready-to-serve and reconciliation exceptions.
- Payment badges never indicate PAID on staff approval alone.
- Actions show actor permission, reason/confirm guard and post-action authoritative state.
- Realtime loss should display "connection lost / refreshing" and recover snapshot.

**Kitchen**
- Orders admitted only when valid, big text and progress buttons, no customer phone/payment credentials.

**Waiter**
- Table occupancy and open/close state; food and game delivery tasks; cash amount/change and confirmation; game copy scan/manual ID.
- Confirm physical handovers; no optimistic marking paid.

**Admin**
- Manage menu, variants, stock, QR/table, game copies, employees/roles and audit.
- Changes show who changed and allow safe deactivate instead of erasing financial history.

## 3. Accessibility, internationalization and resilience

- Initial language: Indonesian. API status codes remain stable English identifiers.
- Label inputs, use appropriate input modes for phone/money, accessible dynamic announcements for payment/order updates.
- Never use color alone to distinguish paid/unpaid. Include text/icons and timestamps.
- Support screen sizes from mobile to desktop, zoom, keyboard on staff desktop and reduced-motion preference.
- Loading, error, no items, sold-out, disconnected, expired visit, access denied and canceled order states are required.
- Preserve customer cart when feasible after transient network errors without creating duplicate order.
- Receipt layout must be printable and legible without decorative backgrounds.

## 4. Design and backend independence

Frontend may evolve freely in look and animation while preserving route/command semantics. API/business actions cannot depend on local browser state, and staff state changes must be server-confirmed.

No layout sizes, brand colors or animation profiles are frozen by these docs. Update this document only when UI functionality or accessibility requirements change.
