# Recover the isolated Aiven review schema after Vercel cold-start timeout

**Status:** 2026-11-06 WIB. This does NOT touch `defaultdb`, production MySQL,
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
git switch --detach origin/fix/m8-hosted-review-one-time-migration
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\migrate-aiven-review-windows.ps1 -HostName "mysql-1e5b1eb2-nafisajuliansahsaputra-811e.b.aivencloud.com" -Port 12886 -CaFile "$env:LOCALAPPDATA\SmartCafe\ca.pem"
```

The `git switch --detach` above does not reset or overwrite your local
branch. If Git detects local uncommitted changes, it refuses switching;
review and save those changes rather than running a forced checkout.

**Maven is no longer a manual prerequisite:** when `mvn` is absent, the
script downloads fixed Apache Maven **3.9.16** from the official HTTPS
Apache distribution, verifies its SHA-512 checksum against the official
checksum file, and extracts it under
`%LOCALAPPDATA%\\SmartCafe\\tools`. It does not edit system PATH or
require administrator privileges. **JDK 21 and the local Aiven CA file
are still required.** Do not run the script if the checksum verification
fails.

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

## Prevention: no destructive Flyway work during HTTP startup

The synthetic MySQL preview now overrides Spring Boot's Flyway migration
strategy with **validation only**. A preview container refuses to start
unless **all 31** ordered MySQL migrations have already succeeded and
there are no pending or failed versions. It does not run `migrate()`,
`repair()`, `clean()` or any other schema-changing Flyway operation.
This protects the currently interrupted V4 schema against retries caused
by public health checks or Vercel cold starts.

Normal PostgreSQL behavior is unchanged. The one-time, explicit Windows
migration script is the only procedure allowed to recover the empty
Aiven review database before the hosted app can start.

## Separate Vercel Container Registry quota blocker (verified 6 Nov 2026)

This is **not** a Maven compilation failure and **not** an Aiven password error.
The exact-head `bff4dfe` Java image built successfully on Vercel, but the
`smart-cafe-api-review` deployments
`dpl_65ygKPDPgxSPVYeMBfLsGEX2uiYv` and
`dpl_9m5dG2xgHSAh5xuHZ7UQsy3qDDZn` failed on registry push with:

```text
denied: repository has reached the maximum allowed number of images
```

The project is on the Hobby plan. Vercel documents a finite image quota.
Repeated **force redeploys do not resolve a full registry** and needlessly
consume build minutes. This is a separate blocker from the incomplete MySQL
V4 migration.

Safe manual Vercel step:

1. Open the `smart-cafe-api-review` project in the Vercel dashboard, then
   inspect **Sandboxes → Container Registry / Images** (menu naming may vary).
2. Review the `dockerfile` repository's stored tags and digests. Identify
   only obsolete builds that are **not referenced by any active/rollback
   deployment**. Remove eligible old images using the dashboard's supported
   management controls. Never delete images still required by a running
   deployment. Do not delete the project or its environment variables.
3. If image deletion is unavailable, use Vercel support or the documented
   registry management UI; **do not create disposable extra projects** to
   evade the quota. Do not retry a push until capacity is available.
4. Separately, complete the one-time local Aiven V1–V31 recovery described
   above. Both the registry capacity and the persistent schema must be
   ready before a new backend Preview deployment can succeed and be tested.

The frontend project `smart-cafe-review` is already **READY** at
commit `bff4dfe`. PR #111's six exact-head GitHub Actions workflows all
passed. **Neither fact proves the hosted Java API is healthy.** The API
remains blocked until the two independent steps above are completed.

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
