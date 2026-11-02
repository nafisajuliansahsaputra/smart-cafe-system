# Local-only initial staff account

This development capability is intentionally **disabled** by default. It does not create any demo/admin account automatically, and there are no default passwords.

1. On a disposable local database, copy `.env.example` to `.env`, set a strong PostgreSQL password.
2. Add `SPRING_PROFILES_ACTIVE=dev-bootstrap`, a unique `SCS_BOOTSTRAP_USERNAME` and `SCS_BOOTSTRAP_PASSWORD` with at least 16 characters to the **gitignored** local `.env`. Explicitly acknowledge the one-time local-only operation with `SCS_BOOTSTRAP_LOCAL_ACK=I_ACCEPT_LOCAL_ADMIN_BOOTSTRAP`. Bootstrap now refuses remote/unexpected PostgreSQL connection strings, any combined/production profile and active sandbox/live payment settings. Only the default local `smart_cafe` database at `db:5432`, `localhost:5432` or `127.0.0.1:5432` is accepted.
3. Run `docker compose up --build` to migrate the database and provision one ADMIN account. The bootstrap intentionally rejects a database containing any existing staff account.
4. **Remove** all four bootstrap environment variables from `.env` and restart with `docker compose up --detach`. The service must start in the normal profile.
5. Use `POST /api/v1/staff/auth/login` with `{"login":"...","password":"..."}` to receive an 8-hour revocable bearer token. Do not save the token in localStorage, shell history, logs or source control. Keep it only in trusted short-lived client memory until a secure staff BFF/cookie flow is implemented.
6. To disable access, change `staff_users.enabled` through an authorized administrative procedure. Existing bearer sessions are immediately rejected. `POST /api/v1/staff/auth/logout` revokes an individual token.

The bootstrap also checks the JDBC URL reported by the **actual Spring
DataSource connection**, not just `DB_URL`. A higher-priority
`SPRING_DATASOURCE_URL`/Spring configuration override cannot redirect
the first ADMIN write to another database while leaving `DB_URL` apparently
safe. URL validation is not network isolation: a hostname such as `db`
must still resolve only inside a trusted disposable local Docker network.
**Flyway runs before this bootstrap runner**; therefore do not start
`dev-bootstrap` on any external or valuable database, even for a safety
test. This gate protects ADMIN provisioning, not every startup migration.

**Strict warning:** this is a local onboarding mechanism only, not a production administrator provisioning or password-recovery system. It is explicitly opt-in by Spring profile and never used in automated tests. Real pilot requires formal staff management, credential lifecycle, TLS, secure browser auth and a reviewed operator recovery path.

No actual payments, personal phones or real cafe operations are supported for public use.


## Staff browser flow on stacked PR #3

With PR #1, PR #2 and PR #3 code combined, and after removing temporary bootstrap variables, open `http://localhost:3000/staff/login`. Enter the locally bootstrapped credentials. Staff identity is verified through the backend; eligible CASHIER/MANAGER/ADMIN roles reach `/staff/orders`. No backend bearer token needs to be copied into browser developer tools or persisted in localStorage. Sessions are stored in a path-scoped HttpOnly cookie and checked against the revocable backend session on every protected page. The browser only sends credentials to the Next.js server action on its own origin.

For non-local deployment, use HTTPS, configure a trusted origin/proxy, and run the full auth and CSRF review before enabling any live operators. Development bootstrap is not a public-user provisioning system.
