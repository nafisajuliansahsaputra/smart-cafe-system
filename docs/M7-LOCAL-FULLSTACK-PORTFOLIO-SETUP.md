# M7: First-run real full-stack cafe on Windows (local-only)

This is an **operator-run** setup for the actual Next.js + Spring Boot +
PostgreSQL website, not the client-only `/showcase` tour. It is intentionally
localhost-only and uses random credentials and synthetic cafe data. Do not
paste real guest phone numbers, merchant credentials or customer records.

## Before starting

- Windows with Docker Desktop and Compose, with the Linux container engine
  running; make sure ports 3000, 8080 and 5432 are available on the host.
- PowerShell 5.1 (built into Windows) or PowerShell 7.
- A **fresh checkout of the integration branch**, not the documentation-only
  `main`. Clone with:

```powershell
git clone --branch integration/m6-v30-release-candidate --single-branch https://github.com/nafisajuliansahsaputra/smart-cafe-system.git
cd smart-cafe-system
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local-fullstack.ps1
```

The script asks you to create a **unique 16–72 character** local administrator
password. It rejects all existing `.env` files, existing Compose containers
and volumes for its reserved isolated project. It does **not** reset any data
or silently attach to another database. It refuses to enable live payments,
public networking or external receipt senders.

## What is actually started

- `http://localhost:3000/` is the real Next.js website.
- `http://localhost:3000/staff/login` is authenticated staff access.
- `http://localhost:8080/api/v1/system/status` is the Spring Boot status.
- PostgreSQL 17 runs privately behind the Docker API; the host port is only
  bound to `127.0.0.1:5432`, not your LAN or the internet.
- One ADMIN account is provisioned with your username/password during the
  **first boot only**. The temporary Spring bootstrap profile and one-time
  password are removed from the local `.env` file in a `finally` block;
  the API container is recreated in the normal profile.
- The fixture adds **12 synthetic menu items, 4 categories, 6 tables and
  3 serialized physical game copies**. There are no demo staff passwords,
  real names, phones, payments or receipts in the SQL.

No migration or seeded data is written to any existing valuable database.
On startup failure the script does not delete data; it stops the initial
bootstrap API process where possible and removes the temporary env secrets.

## Walk through the REAL customer-to-receipt flow

1. Sign in with the ADMIN credentials you entered, then explore catalog
   management at `/staff/catalog` and six table registrations at
   `/staff/tables/registry`.
2. Open table P01 from the protected waiter table panel `/staff/tables`.
   The backend produces a time-limited guest join proof for its active visit.
3. In another browser profile, open `/t/portfolio-01`, verify/join the
   active table with the temporary proof and enter a **fictional** guest name.
4. From `/visit`, order the synthetic menu. Select **cash**, not a bank
   payment. All prices and remaining stock are checked in PostgreSQL.
5. Return to staff OCC `/staff/orders`, approve the cash-due order.
   The order remains **AWAITING_CASH**, not paid.
6. Use the KDS `/staff/kitchen` to start cooking and mark READY. Serve it
   in `/staff/waiter`, then explicitly record test cash with the
   documented idempotent tender flow.
7. The guest's receipt becomes available only after payment is confirmed.
   Inspect cash shifts, game loans, audit and reports using the real staff UI.

The installed administrator can view supervisory surfaces; for separate
role-restricted logins, create synthetic WAITER, KITCHEN and CASHIER accounts
under the ADMIN-only staff management route and activate them through
the audited staff lifecycle. Never publish these credentials.

## Stopping/restarting the isolated project

Run in the repository directory:

```powershell
# Stop containers but preserve the local data volume
docker compose --project-name smart-cafe-local-portfolio down

# Restart safely with the already-sanitized .env and existing database
docker compose --project-name smart-cafe-local-portfolio up --build --detach
```

**Do not use `down -v` unless you have deliberately decided to destroy
only this synthetic local portfolio database.** The setup is first-run only:
after initialization, use the normal `docker compose up` command, not the
bootstrap script. Never reuse the isolated project's credentials elsewhere.

## Verification and limitations

`.github/workflows/local-fullstack-setup.yml` performs an isolated
PowerShell syntax/preflight check, a real Docker first-run using a random
ephemeral CI-only password, checks the twelve items/six tables/three game
copies and the one administrator, ensures the four bootstrap variables
are absent after startup, and destroys only the CI-only disposable project.

This is local browser cash-flow **engineering evidence**, not physical
mobile/NFC/QR testing, a hosted staging service, an approved public POS,
merchant QRIS/VA, or an external WhatsApp/SMS receipt sender. Public release
requires separate staging, backups, privacy, device acceptance, merchant
integrations and explicit owner authorization.
