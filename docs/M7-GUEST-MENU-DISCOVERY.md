# M7: Real guest menu discovery

## Scope and trust boundary

The operational `/visit` menu now supports instant text search and
keyboard-accessible category filters. This is not the public
`/showcase` simulation. The server still validates the guest visit
and retrieves an actual published/available catalog from Spring Boot
and PostgreSQL before rendering the browser. Search is a presentation
operation over that **server-validated snapshot**; it does not expose
private category, hidden products, guest join proof, authentication
credentials or a browser-to-database query.

- Search accepts at most 80 input characters, matching all words
  case-insensitively against item name, category and optional description.
- Category filter state is local to this menu view, with native accessible
  buttons and `aria-pressed` state. It never enters cookies, persistent
  browser storage or external search analytics.
- A clear/reset action, result count and no-match message distinguish
  an empty search from a truly empty catalog or an unavailable API.
- Pricing and stocks come from backend read models, but are always
  checked again by authoritative server-side cart/checkout flows.
- Guests can still browse all available categories with JS hydration;
  no payment or visit lifecycle transition is initiated by filtering.
- The 320px mobile acceptance and the existing cash-to-receipt browser
  acceptance share the same real QR visit; the synthetic guest
  search screenshot is retained as a short-lived CI artifact.

## Evidence and limits

`apps/web/e2e/cash-cycle.spec.mjs` verifies a synthetic waiter-opened
visit, guest join, actual item discovery, multiword search, no-result
state, category reset, responsive width and the ordinary full cash/
kitchen/receipt lifecycle. Real-device screen reader/physical
QR/NFC field testing and hosted pilot remain release gates.

Product images require a separately approved media architecture; no
external image provider was added by this change.
