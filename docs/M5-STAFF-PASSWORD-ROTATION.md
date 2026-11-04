# M5 staff self-service password rotation (draft PR #42)

Scope: staff-only credential hygiene for the existing development system.
No new identity provider, payment behavior or real user data.

- Route: `/staff/account` for any verified staff role, with current password,
  new password and confirmation fields. A successful rotation clears the
  HttpOnly cookie and returns to `/staff/login?status=password-changed`.
- API: `POST /api/v1/staff/auth/password/change`, Bearer-authenticated.
  Current password is checked against BCrypt; new password must be 16–72
  printable ASCII characters, not the same password.
- V23: `staff_password_change_throttles` tracks five failed current-password
  attempts in a 15-minute window per account. Failed attempts commit before
  HTTP errors; success clears the throttle.
- Concurrency: the staff user row is locked for both login credential
  verification/session issuance and password rotation. Rotation also rechecks
  the bearer session under that lock, then updates the hash and revokes all
  staff sessions in one transaction.
- Audit: `STAFF_PASSWORD_CHANGED` contains actor and safe revocation marker,
  never a password, token, digest or password length.

Verification targets: PostgreSQL/Flyway clean migration; negative 401/403/
400/409/429; all-session revocation; old password rejection/new login;
concurrent rotation race; Chromium two-session browser acceptance through
HTTP and HTTPS edge. Check exact PR-head CI before treating tests as passed.

Not in scope: mandatory first-login rotation, forgotten-password recovery,
manager/admin reset, credential delivery, MFA, password policy/legal approval,
or any public/production rollout. Keep staff lifecycle milestone open.
