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

## First administrator (one-time, isolated MySQL schema ONLY)

A fresh Aiven review database contains staff roles after Flyway, but it
contains no staff login. The hosted administrator bootstrap has now been
extended to that **specific** synthetic MySQL database. The process is
transactional and refuses an existing staff user. It locks the permanent
\`ADMIN\` role row \`FOR UPDATE\` on MySQL rather than using unsafe
connection-lifetime \`GET_LOCK\`; PostgreSQL retains its previous
transaction-scoped advisory lock.

**Preconditions:** The backend's real hosted MySQL health and full permission
tests must pass on its isolated \`scs_review_*\` database before inviting
any reviewer. Never use the project's existing \`defaultdb\` or a cafe
production database. The caller must also have the exact four-value
verified TLS truststore JDBC query from the earlier section.

1. Ensure the empty \`scs_review_natsx\` has migrations V1–V31 applied
   and zero rows in \`staff_users\`; take a snapshot first.
2. In the **backend Vercel project**, add these **preview-only** environment
   variables for exactly one bootstrap deployment, without sharing their
   values in a chat, ticket, or GitHub:
   - \`SPRING_PROFILES_ACTIVE=hosted-review-bootstrap\`
   - \`SCS_REVIEW_BOOTSTRAP_ACK=I_ACCEPT_ONE_TIME_HOSTED_REVIEW_ADMIN\`
   - \`SCS_REVIEW_BOOTSTRAP_USERNAME=[review-login, 3–40 safe characters]\`
   - \`SCS_REVIEW_BOOTSTRAP_PASSWORD=[unique random secret, 24–128 characters]\`
3. Keep all \`SCS_MYSQL_REVIEW_*\` and Aiven TLS/certificate settings from
   the previous section. The bootstrap will refuse missing acknowledgements,
   a wrong database/host, weak TLS, enabled payments or an existing staff user.
4. Deploy the review backend once. Verify a **single** ADMIN role assignment
   exists in \`staff_role_assignments\` and that no second bootstrap user
   can be created. Never expose the password in logs or screenshots.
5. **Immediately remove** the bootstrap profile, ACK, username and
   password from Vercel. Trigger a clean normal deployment using only
   \`SCS_MYSQL_REVIEW_ENABLED=true\` and the isolated database settings.
   Never leave a one-time bootstrap profile enabled on a public project.
6. Test staff login, access control, visit opening, checkout, KDS, waiter
   assignment and immutable receipts using **synthetic-only** data.

A Vercel deployment marked \`READY\` proves that the image was deployed,
not that the one-time admin setup or live API operations succeeded.
Do not expose the deployed API as a working POS until those checks pass.

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
