# Isolated zero-required-payment cloud preview (not a merchant production POS)

## Scope

The owner requested a standalone, full-stack Smart Cafe review URL, **not**
publication on NATSX Portfolio. The project must not require a credit/debit
card or monthly hosting payment. This guide uses the provider's *free plans*
(availability, suspension, capacity and pricing can change):

- **Neon Free**: disposable PostgreSQL database.
- **Render Free**: Spring Boot 21 backend service, from this repository's
  `backend/Dockerfile` and root `backend/`.
- **Vercel Hobby**: a **separate** Next.js project from `apps/web/`.
  Never attach the Smart Cafe application to the NATSX Portfolio Vercel
  project or its production domains.

Do not choose paid tiers, paid disks, add-ons, or any plan that requests card
verification unless the owner separately authorizes payment. Avoid Render's
time-limited free PostgreSQL because this project's data must not silently
disappear after trial expiration. Render free services sleep when idle, can
have cold starts and limited RAM. This is not reliable 24/7 cafe production.

## Deploy order

1. Explicitly create a new **empty** Neon Free project for fake data.
   Configure its connection string only in Render's service environment.
   Do not paste credentials in issues, PRs, browser, or CI artifacts.
2. Render: new Web Service using repo
   `nafisajuliansahsaputra/smart-cafe-system`, reviewed release branch,
   runtime **Docker**, Docker context `backend`, Dockerfile
   `backend/Dockerfile`, instance type `Free`, region preferably Singapore.
   Let Render assign `PORT`; Spring accepts `${PORT:8080}`.
3. Render runtime variables, set in its secret manager:
   - `DB_URL=jdbc:postgresql://<Neon-host>/<database>?sslmode=require`.
     Use the **verified exact Neon host and database** from its own project
     instead of guessing, and escape URL special characters as appropriate.
   - `DB_USERNAME` and `DB_PASSWORD`: values issued for the **new disposable**
     Neon database.
   - `SCS_JOIN_THROTTLE_SECRET`: cryptographically random unique 32+
     character value; private to this review environment.
   - `API_DOCS_ENABLED=false`.
   - `SCS_PAYMENT_ENVIRONMENT=disabled`.
   - `SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED=false`.
   - `SPRING_PROFILES_ACTIVE=` (never use `dev-bootstrap` on a public host).
   - `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=20`.
     This conservatively bounds heap on 512 MB Free service; also monitor
     non-heap memory, slow Flyway boot and OOM failures. Do not claim
     successful memory sizing without actual Render startup logs.
   - Optional `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3`,
     `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0` to avoid large connection
     pools against a shared free PostgreSQL allocation.
   No real card payments, merchant webhook key or external messaging enabled.
4. Wait for Render **live** and confirm `/actuator/health` and
   `/api/v1/system/status` report database connectivity on this deployment.
   A passing Docker build without a healthy database does not mean live.
5. Vercel: create a **separate** project `smart-cafe-review`, linked to the
   same reviewed commit/branch, framework Next.js, root `apps/web`,
   **no connection to portfolio**, environment
   `API_INTERNAL_URL=https://<real-backend>.onrender.com`. This variable
   must remain server-only: do **not** rename it to `NEXT_PUBLIC_*`.
   The browser uses same-origin Next.js actions and HttpOnly cookies.
6. Confirm actual deployment SHA, Render/Vercel states, and end-to-end HTTP
   behavior with a **clean synthetic database**. Never substitute the
   in-memory `/showcase` route for backend-integrated testing.

## Single-use FIRST ADMIN on an isolated Neon review database

The `dev-bootstrap` profile is **always local-only**. A separate gated
`hosted-review-bootstrap` profile exists only on the reviewed branch.
Before Flyway touches the database, a BeanFactory postprocessor rejects:

- All remote databases **except** a direct `*.neon.tech` PostgreSQL
  endpoint on the exact, newly created `smart_cafe_review` database.
- Missing TLS `?sslmode=require` or `?sslmode=verify-full`, any extra query
  parameters, a missing exact bootstrap acknowledgement, an invalid admin
  login or a password shorter than 24 characters.
- Production/multiple Spring profiles; backend property datasource overrides;
  **all enabled QRIS/VA/sandbox webhook settings**.
- Any mismatch between the declared database and effective JDBC URL.

On successful Flyway migration, a serializable one-time application runner
takes a transaction-scoped advisory lock, verifies that **no** staff account
exists, and creates exactly one BCrypt-hashed ADMIN account. No HTTP public
bootstrap endpoint exists; no credentials, SQL URLs or hashes enter logs.
A second launch with the profile intentionally fails closed.

For a brand-new private Render + Neon review workspace only:

1. Ensure database name is **exactly `smart_cafe_review`**, not the default
   Neon `neondb`. Do not use a database containing valuable records.
2. Set the standard private JDBC `DB_URL`, DB username/password, payment
   disabled env, random throttle secret as documented above.
3. For the **first deployment only**, add via Render protected service env:
   `SPRING_PROFILES_ACTIVE=hosted-review-bootstrap`
   `SCS_REVIEW_BOOTSTRAP_ACK=I_ACCEPT_ONE_TIME_HOSTED_REVIEW_ADMIN`
   `SCS_REVIEW_BOOTSTRAP_USERNAME=<owner-created safe login>`
   `SCS_REVIEW_BOOTSTRAP_PASSWORD=<new unique password 24-128 chars>`
4. Wait for startup, database Flyway checks and **exact one ADMIN account**.
   Before sharing the URL, **delete all four bootstrap env variables**,
   recreate/redeploy the backend with the normal profile, verify health and
   login using the owner-chosen credential. Never retain plaintext
   administrator credentials in service configuration.
5. Keep the staff login behind a review access restriction where possible,
   enable no merchant provider and use only fabricated guest data.

**This special review bootstrap must never be used on a merchant/production
database or on the NATSX portfolio project.** CI covers its strict
database/host/TLS/profile/payment preflight, while a hosted first-run and
credential-removal walkthrough remains an explicit acceptance gate until
observed on Render.

## Safety and lifecycle

Use only fake order names and cash test entries. Do not publish table join
proofs, staff tokens, QR labels that grant active visits, PII or financial
claims. For trial/review, consider additional authentication in front of the
Vercel frontend or an IP restricted Render service; a public Render backend
remains directly reachable unless explicitly restricted.

Neon capacity, Render Free sleep/quota and Vercel Hobby eligibility depend on
provider policies and usage. Free does **not** guarantee perpetual uptime or
permanent plan availability. Never promise this is zero-cost forever.

Before merging app code to default branch, require Java/PostgreSQL exact-head
CI and a separate human review. Do not change the portfolio repository.

## Render account creation denied (2026-11-05) and no-card fallback

The owner connected Render, but its API refused **even the explicit Free
web-service plan** with `HTTP 402: Payment information is required`.
**Do not try to add a card, switch to a paid tier, or misrepresent the
service as deployed.** This is an account-specific creation block.

A potential no-card alternative is **blitz.cloud Free**, which advertises
HTTPS, Docker/GitHub deploy, 512 MB reserved RAM, and free-plan sleep
after 15 minutes. This is a younger provider and should be independently
assessed for uptime and privacy, not marketed as production-grade.

A separate backend setup can be attempted only after owner registers on
`https://dashboard.blitz.cloud/` and confirms its free plan, without
providing payment details:

1. Create app `smart-cafe-review-api` from the **public** repo
   `https://github.com/nafisajuliansahsaputra/smart-cafe-system`.
2. Choose branch `feat/m7-hosted-review-first-admin` and **Folder to
   build = `backend`**, which contains its `Dockerfile`.
3. Assign 512 MB reserved RAM if allowed; let the service supply `PORT`.
   Existing `backend/Dockerfile` runs as non-root Java 21.
4. Configure environment secrets **in provider dashboard only**, not in
   GitHub or ChatGPT: the dedicated Neon `smart_cafe_review` JDBC URL
   with TLS, DB username and secret, random join throttling key, disabled
   payments, bounded JVM heap and Hikari pool as above.
5. For the **first** launch only, apply the special hosted review profile
   and owner-chosen strong admin secret as described above, then remove
   all temporary bootstrap environment variables before exposing URLs.
   Confirm the live HTTP backend can read migrated database state.
6. Build a separate Vercel project `smart-cafe-review` from the same
   repo's `apps/web` folder, with server-only `API_INTERNAL_URL`
   set to the actual backend HTTPS domain.
7. Before claiming success, perform full cash demo in the real hosted UI
   and verify HTTPS, login, table joining, menu, OCC, KDS, waiter, receipt,
   and failure mode after the free backend sleeps.

Unlike Render, blitz.cloud does **not** have a connected ChatGPT service
connector in this session. Its sign-up, app creation and secret entry must
be completed through its authenticated dashboard by the owner. Do not
invent a deployment URL, and do not present a backend-unavailable
Vercel project as fully functional.

Provider evidence (checked 2026-11-05):
`https://blitz.cloud/docs/deploy-from-github/`,
`https://blitz.cloud/docs/limits/`,
`https://blitz.cloud/terms/`.

Note that Hugging Face Docker Spaces also became subscription-gated for
new users; Koyeb requires a credit card. Neither is a reliable no-card
alternative for this review.
