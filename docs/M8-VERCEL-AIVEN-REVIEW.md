# M8 Vercel container + Aiven isolated MySQL review deployment

**Status 2026-11-06 WIB:** the Next.js presentation frontend is deployed,
and the Java 21 backend Preview now starts successfully with the isolated Aiven
MySQL schema V1–V31. Both hosted read-only endpoints passed owner browser tests;
**the public frontend is not connected to the protected API yet**, and this is
not production merchant approval. This plan never deploys real merchant payment credentials.

## Verified hosted recovery and safe frontend integration (6 Nov 2026)

- Aiven `scs_review_natsx` migration runner returned `BUILD SUCCESS`, 31
  validated migrations, zero failures, and **no reset**. MySQL TLS identity
  was validated locally.
- Backend protected Preview deployment `dpl_ARM94RbkW6AaYVFXZv8wwrytLouV`
  (commit `bff4dfe`) is **READY** after a successful container registry push.
- Owner browser `GET /actuator/health` returned `status: UP`.
- Owner browser `GET /api/v1/system/status` returned
  `database: connected`, `configuredTables: 0`. Despite the field name,
  `configuredTables` is `SELECT COUNT(*) FROM cafe_tables`, so this
  means **zero registered café tables**, not zero SQL schema tables.
- Vercel runtime logs confirm a Spring Boot 4 / MySQL 8.4 Preview cold start
  reaching `scs_review_natsx`, then a clean shutdown. A `READY` image alone
  would not have proved this.
- The `smart-cafe-review` frontend's previously configured
  `API_INTERNAL_URL` remains scoped to the **older** branch
  `fix/m8-vercel-aiven-truststore-startup`; the current
  `fix/m8-hosted-review-one-time-migration` branch does **not** inherit it.

### Cross-project connection (no static bypass secret)

The backend Preview has Vercel Authentication enabled. Browsers already signed
into Vercel can access its endpoints, but server-side Next.js calls from the
frontend must separately authenticate. Do **not** disable backend Deployment
Protection or put an automation-bypass key into a public environment variable.

The frontend now has `apps/web/lib/vercel-trusted-backend.ts`; server-side
`guestApi`, `staffApi`, and `fetchSystemStatus` forward the short-lived
Vercel platform OIDC token as `x-vercel-trusted-oidc-idp-token` only when
running in Vercel Preview and targeting the Smart Cafe backend
`*.vercel.app` hostname. No bearer tokens or passwords are logged or exposed
to client-side code. The backend must still validate every staff/guest bearer
separately. This step is **code-prepared, not yet acceptance-tested**.

Before enabling the integration, an authorized owner must configure the exact
**Trusted Sources** rule under **smart-cafe-api-review → Settings → Deployment
Protection → Trusted Sources**:

- Calling project: `smart-cafe-review`.
- From: `preview`.
- To: `preview`.
- No production/development mappings and no general exceptions.

Then set the frontend server-side `API_INTERNAL_URL` in **Preview** scoped
only to `fix/m8-hosted-review-one-time-migration`, with the exact HTTPS URL
of the **already validated** backend Preview deployment; do not put it into
`NEXT_PUBLIC_*`. Redeploy the frontend Preview. Confirm the frontend server
status returns the expected database and table count without Vercel 401/403,
then verify that unauthorized staff/ops endpoints still deny access before
creating any synthetic staff, café table, order, or cash record.

Previously exposed Aiven credentials must be rotated in the Aiven console and
updated directly in the backend Preview `DB_PASSWORD` variable, without
printing/pasting the new secret anywhere. Check fresh hosted health **after**
the rotation and environment refresh.

## Preview integration evidence and remaining gates (6 Nov 2026)

- `smart-cafe-review` is permitted to reach `smart-cafe-api-review`
  only through Vercel Trusted Sources **Preview → Preview**.
- The original read-only frontend smoke check returned
  `result: passed`, `upstreamHttpStatus: 404` (intentionally nonexistent
  test table), and `workloadIdentityPresent: true`.
- Synthetic Aiven MySQL still validates Flyway V1–V31 without migrating at
  container startup. Review backend `dpl_2TJ7CfYE9og7U13TwAwd9Ko4RtMC`
  responded with HTTP 200 `database: connected`, `configuredTables: 0`;
  anonymous `/api/v1/menu` returned HTTP 200 and an empty catalog.
- The frontend server-side Preview-only `API_INTERNAL_URL` now targets
  `https://smart-cafe-api-review-ngj8esauc.vercel.app`, with
  `dpl_6eMa3ySkcPeJGKFSgx2i3nk9CXNo` as the first integration build.
  It passed the original read-only frontend-to-backend check.
- The backend previously had Vercel Hobby cold-start port timeouts.
  Commit `8ba67f7` reuses a single Flyway history snapshot. Preview
  `SPRING_AUTOCONFIGURE_EXCLUDE` now excludes **both** Hibernate JPA and
  Spring Data JPA repository auto-configuration (neither is used by the
  synthetic JDBC controllers); the first Hibernate-only attempt failed
  because `entityManagerFactory` was still required, and was rolled back.
  Both exclusions apply **only** to the exact synthetic Preview branch,
  not normal PostgreSQL environments. Flyway validation, TLS and payment
  guards remain active.
- The preview-only `/api/review-connectivity` additionally checks that
  menu GET is public, while staff identity and OCC GET return anonymous
  HTTP 401. All tests are read-only; mutating API calls retain their
  original 6-second timeout. No bearer credentials or OIDC tokens appear
  in this endpoint's response.

This does **not** certify always-on reliability, full staff bootstrap,
multi-role operations, digital payment integrations, or merchant production.
Those remain gated on separate live end-to-end tests. Never expose the
review API by disabling Deployment Protection or use live funds.

## Already deployed (separate projects, not the NATSX portfolio)

| Component | Vercel project | URL | Capability |
| --- | --- | --- | --- |
| Frontend | smart-cafe-review | https://smart-cafe-review.vercel.app | Home and isolated browser-only /showcase work without an API |
| Backend image | smart-cafe-api-review | https://smart-cafe-api-review.vercel.app | Dockerfile built and Vercel READY; NOT healthy without MySQL credentials/config |

Both are on the user's own Vercel Hobby team. No credit card, Railway,
Render or paid host was added. Vercel free quotas, possible idle cold
starts and product policy changes are **not** lifetime uptime guarantees.

## Critical hosted migration prerequisite (observed 2026-11-05)

A clean Aiven review schema **cannot be migrated at cold startup on Vercel
Hobby**. Vercel stops waiting for the container to bind its port after
approximately 29 seconds, while Flyway requires substantially longer for
V1-V31. The first real hosted run reached V2; the next run encountered
partially applied V4 DDL (`receipts` already exists). Repeated deployment
or health-check retries will not reliably fix this.

**Do not invoke the review API again until the one-time isolated-schema
recovery is complete.** Follow
[empty-only Aiven review recovery](M8-AIVEN-REVIEW-SCHEMA-RECOVERY.md)
using the reviewed Windows command. It refuses to delete any database
containing staff, menu, customer or financial rows. After it confirms
31 validated Flyway migrations, redeploy the same code using the Preview
branch and only then test `/actuator/health`.

## Before attempting backend startup

The current review gate (PR #104) is **opt-in and fail-closed** by default:

- Do **NOT** use \`defaultdb\`, production/customer records, or an existing
  PostgreSQL database. Manually provision a **new empty isolated Aiven**
  MySQL database named \`scs_review_natsx\`. Use a least-privilege scoped
  database user if the free service supports it. Back up and retain
  existing PostgreSQL/Neon and Aiven databases.
- Aiven has to permit Flyway V1–V31 with triggers (already proved on a
  **disposable** owner-managed Aiven schema). Backups and retention must
  be tested before moving anything real.
- DB credentials, public CA and HMAC key must be entered **directly in
  Vercel project Settings > Environment Variables**, never sent in chat,
  GitHub, a build log or a screenshot.
- **No test may call a live QRIS/VA provider, WhatsApp provider or use
  real personal/financial data.** Checkout sandbox and webhooks stay disabled.
- Full app API + browser E2E, permissions, SSE, admin, reporting, game
  loss, timing and concurrency remain separate acceptance gates.

## Environment for smart-cafe-api-review (preview only)

Set the following on the **backend Vercel project only**, using Preview
environment scoped to the development branch, **after the remaining
backend CI and endpoint audits pass**. Values marked \`[secret]\` must
be entered by the owner. Do not publicly activate API until tested.

| Key | Value |
| --- | --- |
| DB_URL | \`jdbc:mysql://<AIVEN-HOST>:<PORT>/scs_review_natsx?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/tmp/scs-aiven-truststore.p12&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=scs-public-ca-only\` |
| DB_USERNAME | \`[Aiven review DB username]\` |
| DB_PASSWORD | \`[secret]\` |
| SCS_AIVEN_CA_PEM_BASE64 | \`[base64 contents of the Aiven PUBLIC ca.pem]\` |
| SCS_JOIN_THROTTLE_SECRET | \`[independent secret of >=32 bytes]\` |
| SCS_MYSQL_REVIEW_ENABLED | \`true\` |
| SCS_MYSQL_REVIEW_ACK | \`SCS_MYSQL_SYNTHETIC_REVIEW_ONLY\` |
| SCS_FLYWAY_LOCATIONS | \`classpath:db/mysql\` |
| SCS_PAYMENT_ENVIRONMENT | \`disabled\` |
| SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED | \`false\` |
| API_DOCS_ENABLED | \`false\` |

Only the Aiven PUBLIC cert is base64 encoded: base64 is not encryption,
so **never** use it for DB passwords. With the existing CA file on the
owner's Windows machine, encode only when setting the Vercel env value:

\`\`\`powershell
[Convert]::ToBase64String(
  [System.IO.File]::ReadAllBytes("$env:LOCALAPPDATA\SmartCafe\ca.pem")
)
\`\`\`

Paste the generated CA text into Vercel directly. The container writes
it to a restrictive temporary CA file, imports that cert into a
temporary PKCS12 Java truststore and removes the temporary PEM before
starting Java. Only the public CA is in that truststore; the fixed
\`scs-public-ca-only\` password is NOT a merchant or DB password.

**Manual safety gate:** before assigning API_INTERNAL_URL on the frontend,
verify the backend's \`/actuator/health\`, strict JDBC TLS identity,
MySQL Flyway location, authenticated role checks, financial negative
tests and guest-to-cash workflow against the **isolated schema**. Do not
disable default startup guard for an unscoped MySQL host or customer DB.

When successful, configure the **frontend** server-only
\`API_INTERNAL_URL\` as the HTTPS backend deployment URL. Avoid
\`NEXT_PUBLIC_...\` for a backend credential endpoint. Keep any staff
and guest session tokens HttpOnly and never expose DB credentials to
the browser.

## First ADMIN and fictional catalog: one owner-local operation

**Status as of 11 Nov 2026 WIB:** Mechanism prepared and tested in PR #111,
but **not yet executed against Aiven**. The real Aiven schema has valid
Flyway V1–V31, and the protected Preview frontend/backend connection passed
its read-only test.

The owner does **not** need to create tables, products, stock or game
copies manually. The Windows bootstrap can atomically create:

- One ADMIN, chosen privately by the owner (BCrypt/12, no default login).
- Six fictional café tables, four menu categories and twelve products.
- 200 synthetic stock units per item, Size and Milk modifiers on Spanish Latte.
- Two physical game types and three serialized game copies.
- No guest identities, orders, money, payment attempts or receipts.

Only run this once in the isolated Aiven `scs_review_natsx` schema.
The required conditions are: (1) rotate any previously disclosed Aiven
database password and update the backend Preview `DB_PASSWORD`; (2) make a
backup/snapshot; (3) confirm the service is reachable, the CA file exists and
the current review branch is checked out; (4) enter the secrets in hidden
PowerShell prompts, **never into GitHub, screenshots, URLs or chat**.

From the checked-out `smart-cafe-system` review branch:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\provision-aiven-review-admin-windows.ps1 -HostName "mysql-1e5b1eb2-nafisajuliansahsaputra-811e.b.aivencloud.com" -Port 12886 -CaFile "$env:LOCALAPPDATA\SmartCafe\ca.pem" -SeedSyntheticCatalog
```

The single approval phrase for this option is
`CREATE-ONE-SYNTHETIC-REVIEW-ADMIN-AND-CATALOG`. Choose a 3–40
character ADMIN username, enter the current Aiven password, then enter
a different 24–128 character ADMIN password. Confirmation can be retried
three times **before any database connection or write**, without retyping
the Aiven password. Passwords are never stored in the repository.

The Java runner checks the exact database catalog, verified CA/TLS
hostname, **all 31 successful and checksum-validated Flyway migrations**,
the presence of the `ADMIN` role, zero existing staff, and (when the
seed option is enabled) zero existing business records. It then locks
the ADMIN role, creates the account, and inserts all fictional inventory
in **one InnoDB transaction**. Any partial failure rolls back both the
account and catalog. Flyway `clean()`/`migrate()` are never invoked.

Expected final messages:

```text
PASS: exactly one review ADMIN provisioned transactionally.
PASS: six tables, 12 menu items, 4 categories, 2 games, 3 copies seeded atomically.
```

The seeding SQL and rollback were verified in disposable MySQL 8.4 with
`MySqlSyntheticCatalogSeedTest`, alongside the public menu endpoint with
real MySQL UUID mappings. This proof does **not** mean an ADMIN has been
created on Aiven or that hosted staff-to-receipt operations are accepted.

For an empty review where **only** the ADMIN is required, omit
`-SeedSyntheticCatalog` and use the original authorization phrase
`CREATE-ONE-SYNTHETIC-REVIEW-ADMIN`. Do not run either form a second
time after success.

After the one-time setup, use protected Preview `/staff/login` to
verify account login, role access, logout and audit, then run a
dummy-money full operations cycle (table → QR guest → menu/cart → cash
order → OCC approval → KDS → waiter → paid-only receipt). Use **no real
merchant funds or personally identifying guest data**. Production QRIS/VA
provider onboarding, external receipt sender, real-device/tablet UX,
backup recovery, and owner release acceptance remain separate gates.
The educational `/showcase` is not proof of real operations.

## Deployment evidence

- [PR #103 tests](https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/41935747458):
  MySQL Testcontainers 26/26, zero errors.
- Frontend deployment: \`dpl_GTqxGACueokP9YrxokWAq4nx75Bn\`.
- Backend Docker image: \`dpl_E16b4bTmM9fGMGtyX7CsJVVXUNZi\`,
  Vercel reports READY but runtime health is **not yet verified**.
- The original PostgreSQL backend defaults remain unchanged. Real
  payment-provider credentials and portfolio remain untouched.

**Not production approval.** An isolated preview is for synthetic
operator review only. Explicit owner consent is required for a real
merchant rollout or database cutover.
