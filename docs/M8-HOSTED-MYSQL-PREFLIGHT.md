# M8 hosted MySQL 8 preflight (Aiven Free, review only)

This check verifies whether a **new, disposable** remote MySQL 8 service can
safely run the database primitives needed by Smart Cafe System. It does **not**
start Spring Boot, move PostgreSQL data, run Flyway, change hosted credentials,
or authorize real merchant payments. The backend's MySQL fail-closed guard
must remain enabled until full transaction and business parity is verified.

## Provider decision and no-card budget

[Aiven for MySQL Free](https://aiven.io/docs/products/mysql/concepts/mysql-free-tier)
advertises a no-credit-card, no-expiry free plan: 1 GB storage, 1 GB RAM,
1 CPU and automatic backups. A free service may be paused after inactivity
and is not backed by the paid SLA; plan availability and policies can change.
This is suitable only for **isolated review data**, not a guaranteed
always-on production cafe. Check the actual dashboard plan before provisioning.

**Billing guard:** Aiven **Developer** plans commonly have **8 GB** storage
and charge after trial credits run out. Aiven **Free** provides **1 GB**
storage at **$0/month**. A green banner for 30-day trial credits does **not**
make an 8 GB Developer service free forever. Verify the service is really
Free in Aiven > Overview > Service plan usage, and explicitly switch plans
using **Change plan** when needed. Do not add a debit/credit card.

The principal unresolved provider condition is `CREATE TRIGGER` with
binary logging. MySQL can refuse trigger creation (error 1419) when
`log_bin_trust_function_creators=0` and the user has no appropriate privilege.
Aiven documents a `TRIGGER` grant for databases created by a service user,
but that by itself does not prove the binary-logging requirement on this plan.
**Do not strip immutable receipt/payment or audit triggers to get past it.**

## Prerequisites

1. Create a fresh **MySQL 8** Aiven Free service in a separate review project.
   Do not copy or delete Neon/PostgreSQL data; never use live customer records.
2. Install a MySQL 8 command-line client (`mysql.exe`) on Windows.
3. Download the provider's CA certificate, and create a client option file
   **outside** the repository, for example
   `$env:LOCALAPPDATA\SmartCafe\mysql-review.cnf`. Restrict access to
   your Windows account. Do not commit, screenshot or paste its credentials.
4. Example option-file structure (replace the placeholders locally):

```ini
[client]
host=your-aiven-host
port=your-aiven-port
user=your-dedicated-review-user
password=your-secret-password
ssl-mode=VERIFY_IDENTITY
ssl-ca=C:/Users/you/AppData/Local/SmartCafe/ca.pem
```

Do not downgrade to `ssl-mode=DISABLED` or remove hostname validation to
make a failed connection pass.

## Fast path: guided Windows connection to your Aiven Free service

The Aiven dashboard must show **Free-1-1gb** and service status
**Running** before connecting. "Rebalancing" is the plan-change
transition, not a successful MySQL connection.

From a checkout of this branch on a Windows PC with MySQL 8 CLI installed,
download the **Aiven CA certificate** from Overview > Connection
information into the private Windows directory
`$env:LOCALAPPDATA\SmartCafe\ca.pem`. Then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-aiven-review-windows.ps1 `
  -HostName "YOUR_SERVICE_HOST.aivencloud.com" `
  -Port YOUR_SERVICE_PORT `
  -CaFile "$env:LOCALAPPDATA\SmartCafe\ca.pem"
```

It interactively prompts for the Aiven password using the official MySQL
client editor, stores it in a dedicated local login-path and performs only
**read-only** TLS/version/trigger-setting checks. It never runs Flyway or
uses merchant records. If the read-only test passes, rerun with
`-RunWriteProbe` and type `SCRATCH-ONLY` at the explicit
confirmation to test isolated CREATE TRIGGER and strict CHECK enforcement.
The random scratch database is dropped after the test. If cleanup fails,
do not run it again blindly; examine the named scratch schema first.

## Windows setup: MySQL login-path (preferred)

After the service status is **Running** and you confirm the **Free**
plan, download the CA certificate from Aiven > Overview > Connection
information. Save it outside the repository, for example at
`$env:LOCALAPPDATA\SmartCafe\ca.pem`.

Install the official MySQL 8 client (including `mysql_config_editor`).
Run these commands in **local** PowerShell, replacing the host and
port with the connection information shown by Aiven:

```powershell
# One-time interactive MySQL login-path setup.
# The password prompt is local; no password appears in shell history.
mysql_config_editor set --login-path=smartcafe_review --host=YOUR_AIVEN_HOST.aivencloud.com --port=YOUR_AIVEN_PORT --user=avnadmin --password

# Read-only TLS and server-prerequisite verification.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql-host-preflight.ps1 `
  -LoginPath smartcafe_review `
  -CaFile "$env:LOCALAPPDATA\SmartCafe\ca.pem" `
  -ExpectedHost "YOUR_AIVEN_HOST.aivencloud.com"

# Opt in only on a disposable, non-production Aiven review service:
# creates one random scratch database, tests triggers and CHECK, deletes it.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql-host-preflight.ps1 `
  -LoginPath smartcafe_review `
  -CaFile "$env:LOCALAPPDATA\SmartCafe\ca.pem" `
  -ExpectedHost "YOUR_AIVEN_HOST.aivencloud.com" `
  -RunWriteProbe
```

The MySQL login-path file (`.mylogin.cnf`) stores an **obfuscated**
password, not strongly encrypted data. Restrict file access to your
Windows account; never upload the file. To remove this local credential,
run `mysql_config_editor remove --login-path=smartcafe_review`.
Rotate the remote password through Aiven if it is exposed.

### Alternative: a local-only MySQL client option file

If `mysql_config_editor` is unavailable, use the option file
described in the prerequisites section, with restricted NTFS permissions:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql-host-preflight.ps1 `
  -DefaultsFile "$env:LOCALAPPDATA\SmartCafe\mysql-review.cnf" `
  -ExpectedHost "YOUR_AIVEN_HOST.aivencloud.com"

# Add -RunWriteProbe only when deliberately authorizing temporary DDL.
```

The script does not use `--password=...` on the command line and does not
print credentials. Write mode requires an explicit Aiven `-ExpectedHost`
to avoid relying on hidden client defaults. It always requires an active TLS connection and genuine
MySQL 8.x. It refuses MariaDB and TiDB compatibility proxies as equivalent
proof. The write probe validates actual creation and firing of a trigger,
plus a database-level rejection of lowercase `paid` compared with `PAID`.
The write probe exits nonzero on failed permissions, unsafe CHECK behavior,
insecure connection or cleanup errors. If cleanup fails, it prints the
precise isolated schema name to remove manually. Never target a production
server or use your existing cafe database to run this probe.

## Real Aiven Free read-only acceptance (2026-11-05 WIB)

Owner-supplied Windows PowerShell output confirms the service is MySQL
**8.4.11**, an authenticated connection with the correct Aiven CA under
`--ssl-mode=VERIFY_IDENTITY`, active negotiated cipher
`TLS_AES_128_GCM_SHA256`, `log_bin=1`, and
`log_bin_trust_function_creators=1`. The preflight printed
`PASS: read-only server and TLS checks.` The owner separately
confirmed Aiven's `Free-1-1gb` service plan.

**This proves ONLY remote connection + TLS + basic server variables.**
Trigger creation, CHECK enforcement, hosted V1–V31 Flyway migrations,
full MySQL SQL/financial transaction parity, backend first-admin login,
public frontend and backend deployment remain **UNVERIFIED**.
Never interpret the successful read-only check as merchant readiness.

The next review acceptance step uses the saved *local* MySQL login-path;
no password is included in a script or committed:

```powershell
# Run only against the disposable, empty Aiven Free review service.
# This explicitly creates, tests, and deletes a random scs_probe_* database.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql-host-preflight.ps1 -LoginPath smartcafe_review -CaFile "$env:LOCALAPPDATA\SmartCafe\ca.pem" -ExpectedHost "YOUR_SERVICE_HOST.aivencloud.com" -RunWriteProbe
```

Capture the non-secret output and stop if the host rejects CREATE DATABASE,
CREATE TRIGGER, or the strict financial CHECK. Do not alter protected
money/stock triggers as a workaround. An unsuccessful probe is **not**
a reason to copy PostgreSQL/Neon data or switch the backend to MySQL.

## Acceptance and follow-on gates

| Gate | Acceptance evidence |
| --- | --- |
| TLS | Session reports nonempty `Ssl_cipher`; the client enforces `VERIFY_IDENTITY` and uses the CA |
| Billing | Confirm **Free**, **1 GB**, and **$0/month**; Developer 8 GB is a paid tier after trial |
| Version | Genuine MySQL 8.x, not simply a MySQL-compatible wire protocol |
| Trigger privileges | One scratch trigger created, fired with intended error, and dropped |
| Financial state collation | `PAID` accepted, lowercase `paid` rejected by database CHECK |
| Cleanup | Scratch database dropped; no shared schema modified |
| Flyway V1-V31 | **NOT covered:** must pass exact-head CI and isolated hosted upgrade test |
| Business SQL parity | **NOT covered:** transaction-safe PG advisory-lock replacements, UUID binding, JSON/SQL differences and all cash, game, KDS and security E2E |
| Public deployment | **NOT covered:** API boot remains blocked on MySQL until above gates are passed |

The host probe is independent of the core `MySqlCoreParityIntegrationTest`
against disposable MySQL Testcontainers. Both are necessary but insufficient
for certifying Smart Cafe System on MySQL.

## Failure policy and rollback

If SSL, trigger privileges or strict state checks fail, **stop**. Keep the
PostgreSQL backend as the functional development baseline. Do not auto-migrate
data, override MySQL server variables, change protected ledger triggers,
or point any public website to the new MySQL service.

No migration is performed by this runbook; rollback is to leave the existing
PostgreSQL development setup untouched, and delete the unused review
database only after verifying that it contains no legitimate data.
