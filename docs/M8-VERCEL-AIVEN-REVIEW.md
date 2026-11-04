# M8 Vercel container + Aiven isolated MySQL review deployment

**Status 2026-11-05 WIB:** the Next.js presentation frontend is deployed
and the Java 21 backend image builds on Vercel Container. **The backend
has NOT been connected to Aiven**. The public site is not yet a working
full-stack café. This plan never deploys real merchant payment credentials.

## Already deployed (separate projects, not the NATSX portfolio)

| Component | Vercel project | URL | Capability |
| --- | --- | --- | --- |
| Frontend | smart-cafe-review | https://smart-cafe-review.vercel.app | Home and isolated browser-only /showcase work without an API |
| Backend image | smart-cafe-api-review | https://smart-cafe-api-review.vercel.app | Dockerfile built and Vercel READY; NOT healthy without MySQL credentials/config |

Both are on the user's own Vercel Hobby team. No credit card, Railway,
Render or paid host was added. Vercel free quotas, possible idle cold
starts and product policy changes are **not** lifetime uptime guarantees.

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
