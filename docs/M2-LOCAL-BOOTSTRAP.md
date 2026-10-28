# Local-only initial staff account

This development capability is intentionally **disabled** by default. It does not create any demo/admin account automatically, and there are no default passwords.

1. On a disposable local database, copy `.env.example` to `.env`, set a strong PostgreSQL password.
2. Add `SPRING_PROFILES_ACTIVE=dev-bootstrap`, a unique `SCS_BOOTSTRAP_USERNAME` and `SCS_BOOTSTRAP_PASSWORD` with at least 16 characters to the **gitignored** local `.env`.
3. Run `docker compose up --build` to migrate the database and provision one ADMIN account. The bootstrap intentionally rejects a database containing any existing staff account.
4. **Remove** the three bootstrap environment variables from `.env` and restart with `docker compose up --detach`. The service must start in the normal profile.
5. Use `POST /api/v1/staff/auth/login` with `{"login":"...","password":"..."}` to receive an 8-hour revocable bearer token. Do not save the token in localStorage, shell history, logs or source control. Keep it only in trusted short-lived client memory until a secure staff BFF/cookie flow is implemented.
6. To disable access, change `staff_users.enabled` through an authorized administrative procedure. Existing bearer sessions are immediately rejected. `POST /api/v1/staff/auth/logout` revokes an individual token.

**Strict warning:** this is a local onboarding mechanism only, not a production administrator provisioning or password-recovery system. It is explicitly opt-in by Spring profile and never used in automated tests. Real pilot requires formal staff management, credential lifecycle, TLS, secure browser auth and a reviewed operator recovery path.

No actual payments, personal phones or real cafe operations are supported for public use.
