# Digital Receipts and Phone Messaging

**Status:** Product/technical design. In-app receipts are the baseline; external phone messaging is a later milestone and **not yet connected**.

## 1. Product decision

Customers may enter a phone number **to receive a receipt**. The number is **optional** and never blocks order submission or in-app receipt viewing. Receipt sending is a separately explained optional feature, not a marketing subscription.

Default flow:
- Customer joins table, enters nickname and optionally number.
- Customer sees an explicit setting: "Kirim struk ke nomor ini" with chosen channel if available.
- The system stores the purpose, notice/version and opt-in time securely.
- Upon **confirmed PAID** (digital gateway verification or actual cash collection), the server finalizes receipt.
- In-app view/download always available; if an approved provider is configured and opted in, enqueue a message.
- Delivery error leaves payment/receipt intact and offers resend or download.

Do not promise automatic WhatsApp messages before a WhatsApp Business Platform account, provider, template approvals and costs are validated.

## 2. Phone UI and privacy

- Label: "Nomor telepon (opsional, untuk struk digital)".
- Normalize to E.164 (e.g. +62...), with strict server-side validation; prefer a phone number parsing library.
- Explain channel, purpose and expected message; optional independent checkbox not prechecked.
- Do not automatically treat customer number as permission for promotions.
- Store only needed data; encryption and access control for phone, masked in OCC by default.
- Users may decline phone and still order/pay/access receipt.
- Allow consent revocation for future sends; do not retroactively alter legally required accounting records.
- Do not include plain phone in query strings, receipt links, analytics or structured logs.

Before real deployment, define retention/privacy notice, lawful basis, deletion/rights handling and provider processing agreement consistent with applicable Indonesian law.

## 3. Receipt identity and content

Immutable receipt contains:
- merchant/cafe display identity and transaction date/time;
- receipt number, order number, table reference (safe abbreviated), server-authoritative purchased items, variants, quantity and unit price;
- subtotal, discounts, tax/service fees as configured, grand total, currency IDR;
- actual payment method and paid timestamp;
- documented adjustment/refund references if later corrected;
- no full phone, payment credential, private identifiers or gateway auth values.

Receipt must not be issued for PENDING/UNPAID. Served food with unpaid cash must not show a paid receipt. A provisional order summary is distinct from receipt.

## 3a. In-session printable receipt (draft PR #38)

The guest-owned and PAID-only `/visit/orders/[id]/receipt` page renders a full server-validated receipt with immutable line snapshots, quantities, unit prices, variants, issue time, receipt number and final IDR total. A client-only print button invokes the browser's native print dialog so the guest can print or choose **Save as PDF** without an external PDF service, persistent public link or new payment endpoint.

Access requires the existing short-lived, visit-scoped HttpOnly guest session. Unknown, unpaid and foreign orders return 404; a missing/expired session redirects to guest entry; upstream errors never fabricate a receipt. No phone number, bearer token or remote analytics is included. Chromium acceptance on synthetic HTTP and HTTPS fixtures checks these deny paths and the paid print view. This capability does not include automated WhatsApp/SMS, payment gateway integration, tax breakdown not present in the receipt API, or post-visit token sharing. Keep public pilot blocked until provider and privacy approvals.

## 4. Channel strategy

**MVP:** authenticated in-visit HTML receipt + print/download PDF/print stylesheet. The capability to share a safe short link can be added after review.

**Post-MVP:** ReceiptSender interface:
- Provider candidate A: WhatsApp Business Platform/template (requires eligible business account, permitted templates, customer opt-in and message costs).
- Provider candidate B: transactional SMS vendor (evaluate cost, delivery and compliance).
- No assumption that a normal personal WhatsApp link/automation is a compliant server-to-customer messaging API.
- Select one provider by ADR based on availability, rates and obligations. Fail gracefully if unavailable.

Provider adapter accepts receipt ID and abstract delivery request; never price, payment status or authoritatively edit receipts.

## 5. Durable delivery mechanism

~~~text
confirmed PAID DB transaction
 -> finalize immutable receipt
 -> create receipt_dispatch + outbox event (if consent)
 -> worker claims job
 -> provider adapter sends approved message/link
 -> store message ID + send result
 -> retry transient failures with backoff/limit
 -> manual failure state, not order rollback
~~~

Use dedupe key (receipt_id, channel, explicit dispatch intent). A user's intentional resend creates a separate idempotent intent with throttle. Delivery can be SENT but not DELIVERED unless provider confirms; read receipts are not assumed.

## 6. Secure receipt link

- Generate cryptographically random bearer token, store hash only.
- Short TTL and revoke/rotation; optional single-purpose tokens.
- No guessable sequence or direct phone/receipt ID in unauthenticated URL.
- Read-only and rate limited; minimal disclosure on token view.
- Explicit no-store/cache and Referrer-Policy controls as appropriate.
- On expiry, offer authorized guest retrieval or staff help, not unrestricted search by phone.
- Payment and phone records must not be accessible from guessed order ID.

## 7. Failure acceptance tests

1. Guest declines phone: order/payment works; in-app receipt visible.
2. Number invalid: clear correction, can proceed without number.
3. Cash order not collected: no finalized receipt and no external message.
4. Webhook duplicate: only one immutable sale receipt, no duplicate outbound send.
5. Message provider unavailable: paid state intact, job failed/retryable and customer can download.
6. Opt-out before dispatch: job canceled.
7. Expired receipt link: no receipt disclosure; no phone leak.
8. Wrong guest or unauthorized staff cannot retrieve private receipt data.
9. Resend button throttled, idempotent and audited as needed.
10. Refund/correction creates traceable adjustment, never quietly edits receipt.

## 8. External references to verify during integration

- Midtrans notification documentation: https://docs.midtrans.com/docs/https-notification-webhooks
- Midtrans notification authenticity: https://docs.midtrans.com/reference/handle-notifications
- Indonesia Personal Data Protection Law (UU 27/2022): https://jdih.komdigi.go.id/produk_hukum/view/id/832/t/crc32/

Provider-specific WhatsApp/SMS rules and approved message templates must be reverified from the selected provider's current documentation at implementation time.
