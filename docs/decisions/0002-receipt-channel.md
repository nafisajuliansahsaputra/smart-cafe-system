# ADR 0002: Optional Phone Receipt, In-App First, Messaging by Approved Adapter

**Status:** Accepted baseline; provider selection pending  
**Date:** 2026-10-29  
**Project:** Smart Cafe System

## Context

Product owner wants customers to enter a phone number so cafe receipts can be delivered after ordering. Automated WhatsApp/SMS entails identity, personal-data, consent, message cost, templates and third-party delivery reliability.

## Decision

1. Phone number is optional and only collected for clear purposes.
2. A finalized paid receipt is always viewable/downloadable from the authorized guest session, regardless of phone or messaging delivery.
3. Before an external receipt message is sent, record purpose-specific consent and chosen available channel; marketing consent is separate and never presumed.
4. Cash order receipt is issued after real tender collection, not when waiter approves the kitchen order.
5. Receipt is immutable; adjustment/correction produces linked record.
6. Use a durable transactional outbox and a ReceiptSender adapter. Provider errors must not change paid state.
7. Evaluate WhatsApp Business Platform utility template vs transactional SMS and cost/approval requirements before selecting vendor.
8. Shared receipt URL uses limited-scope random expiring tokens (hash stored), no phone in URL.

## Consequences

Customer checkout stays usable even without phone, WhatsApp account or provider uptime. The app must implement authorization, privacy notice/retention, deduplication and delivery attempts rather than firing a synchronous SMS API call on checkout.

## Unresolved checks

- Preferred channel and sender provider.
- Current messaging template rules and approval.
- Per-message cost budget and rate limits.
- Receipt format/brand identity.
- Specific legal retention policy and privacy notice for actual merchant use.

## References

- Product flow: ../../PRD.md
- Receipt contract: ../RECEIPTS.md
- Privacy baseline: ../SECURITY.md
- Indonesian UU PDP: https://jdih.komdigi.go.id/produk_hukum/view/id/832/t/crc32/
