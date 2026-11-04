# M7 portfolio operator evidence (stacked draft)

## Goal
Produce reproducible screenshots of **real operational pages backed by Spring
Boot/PostgreSQL**, so the portfolio case study does not rely on the client-only
`/showcase` for evidence of cash, kitchen and waiter functionality.

## Artifact
The full Chromium CI suite already opens a disposable PostgreSQL instance and
authenticates separate synthetic WAITER, CASHIER and KITCHEN users. The existing
cash-order E2E now saves these additional PNGs as part of the successful
`portfolio-showcase-screenshots` artifact (7-day retention):

| File | What it proves | What it does NOT prove |
| --- | --- | --- |
| `real-occ-awaiting-cash.png` | Unpaid guest order appears in authenticated cashier OCC via live server update | Cash was received |
| `real-occ-approved-cash-due.png` | Cashier authorizes kitchen preparation, cash still `AWAITING_CASH` | Order is paid |
| `real-kds-preparing.png` | Authorized kitchen staff sees and advances real ticket | Production kitchen or device readiness |
| `real-waiter-ready.png` | Waiter receives READY ticket through real SSE state | Physical food delivery happened |
| `real-guest-paid-receipt.png` | After test cash capture, backend returns authorized finalized receipt | Genuine merchant payment, fiscal certification or messaging delivery |

`operational-guest-menu-search.png`, manager catalog/table/audit/QR evidence
and client-only `portfolio-*.png` images remain separately labeled.

## Safety constraints
- Screenshots are taken only from `localhost` or `127.0.0.1`, only from
  expected role routes, after confirmed server assertions; they refuse
  password fields and visible join proof. The CI fixtures use synthetic staff,
  guest, amounts, tables and items. No external uploads or customer data.
- Do not photograph login/password, staff bootstrap, join proof or any real
  customer phone/order. Do not share traces containing credentials.
- Image artifacts are retained temporarily, require review, and must never
  be represented as a real production settlement, device certification or
  hosted live system.
- Screenshot retention is short intentionally; repeat an isolated CI run on
  an authorized feature branch if screenshots need to be regenerated.

## Acceptance
The browser test must pass the original checkout, cross-guest denial,
unpaid receipt denial, cash approval, SSE offline/reconnect, kitchen/waiter
handover and paid receipt assertions. Screenshots are generated only as an
additional effect on that successful flow. The CI artifact upload is gated on
the entire browser job succeeding. The full integration branch must also
pass exact-head checks before a release or portfolio publication.
