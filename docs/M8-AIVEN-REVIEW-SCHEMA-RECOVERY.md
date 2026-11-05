# Recover the isolated Aiven review schema after Vercel cold-start timeout

**Status:** 2026-11-05 WIB. This does NOT touch `defaultdb`, production MySQL,
Neon/PostgreSQL or live merchant payments. The target is fixed to **`scs_review_natsx`**.

## Why the API is not ready yet

- 11 Vercel environment keys were scoped to the exact Preview branch.
- The Aiven public CA truststore startup defect was fixed in PR #110 and its
  exact-head full CI passed.
- Live Vercel review request reached Aiven with strict TLS and began Flyway.
- Vercel's 28.6-second startup-port deadline fired before Spring Boot
  finished the 31 migrations, yielding `FUNCTION_INVOCATION_FAILED`.
- A second request found partial nontransactional MySQL V4 DDL and failed
  with `Table 'receipts' already exists`. **Do not keep refreshing the
  endpoint; automatic retries can corrupt the synthetic schema further.**

## Safe recovery, one time, from owner's Windows machine

The user has already configured a MySQL `smartcafe_review` login path
and saved `ca.pem` locally. Use the repository's complete branch
`fix/m8-hosted-review-one-time-migration` (PR #111). Do not execute an
old branch that lacks this strict empty-only check.

Run from `C:\Users\nafis\smart-cafe-aiven`:

```powershell
git fetch origin
git switch -C fix/m8-hosted-review-one-time-migration origin/fix/m8-hosted-review-one-time-migration
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\migrate-aiven-review-windows.ps1 -HostName "mysql-1e5b1eb2-nafisajuliansahsaputra-811e.b.aivencloud.com" -Port 12886 -CaFile "$env:LOCALAPPDATA\SmartCafe\ca.pem"
```

The script will check Aiven TLS and MySQL, then ask for the exact local
confirmation `RESET-EMPTY-SYNTHETIC-REVIEW` before any potential schema
cleaning. Enter the Aiven DB password **only in its hidden local prompt**.
It is passed solely as an inherited environment variable to the short-lived
local Maven JUnit runner, then cleared. **Never paste the DB password into
GitHub, a screenshot or this chat.**

The Maven runner:

1. Uses fixed schema `scs_review_natsx` and fully verified public Aiven CA.
2. Checks for a schema and TLS without modifying `defaultdb`.
3. If all 31 migrations already completed, validates and exits without
   cleaning.
4. Otherwise checks *every base table* for data. The only nonempty
   tables allowed are default migration-generated `staff_roles`
   (exactly five original roles) and `flyway_schema_history`.
   **ANY records in customer, staff, menu, order, stock, cash, loans,
   audit or other tables abort the operation without cleanup.**
5. Cleans only the incomplete, otherwise empty synthetic schema with
   Flyway's own MySQL clean support, then migrates V1–V31 through the
   full local Java process, which has no Vercel's 29-second HTTP startup
   deadline.
6. Validates 31 successes, receipt paid guard, image schema and repeated
   no-op migration. The completed database is persistent, not dropped.

**Success criterion:** the local console prints
`PASS: isolated Aiven scs_review_natsx V1-V31 verified.`

## After successful completion

The owner can return here with only a success screenshot (hide usernames,
host secrets, certificates and passwords). The assistant will then
redeploy the reviewed Java container on Preview, verify real
`/actuator/health`, `/api/v1/system/status`, denial endpoints,
initialize the one-time admin securely and connect the frontend server's
`API_INTERNAL_URL` after a full hosted browser safety review.

The UI at `https://smart-cafe-review.vercel.app` is still an online
frontend showcase; **the backend is not yet a functioning full-stack API**.

No paid hosting tier, credit card, real merchant credentials or portfolio
modification is permitted. Never deploy a production database or treat
synthetic tests as live payment processor certification.
