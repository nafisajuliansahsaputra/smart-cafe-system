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

## Read-only first, then explicitly opt into disposable DDL

From a checkout of this PR branch:

```powershell
# Read-only metadata and TLS checks. No tables or databases created.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql-host-preflight.ps1 -DefaultsFile "$env:LOCALAPPDATA\SmartCafe\mysql-review.cnf"

# Only after verifying the selected remote host is an EMPTY review provider:
# Creates one random scs_probe_* database, tests a trigger and financial
# case-sensitive CHECK, then drops only that scratch database.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql-host-preflight.ps1 -DefaultsFile "$env:LOCALAPPDATA\SmartCafe\mysql-review.cnf" -RunWriteProbe
```

The script does not use `--password=...` on the command line and does not
print credentials. It always requires an active TLS connection and genuine
MySQL 8.x. It refuses MariaDB and TiDB compatibility proxies as equivalent
proof. The write probe validates actual creation and firing of a trigger,
plus a database-level rejection of lowercase `paid` compared with `PAID`.
The write probe exits nonzero on failed permissions, unsafe CHECK behavior,
insecure connection or cleanup errors. If cleanup fails, it prints the
precise isolated schema name to remove manually. Never target a production
server or use your existing cafe database to run this probe.

## Acceptance and follow-on gates

| Gate | Acceptance evidence |
| --- | --- |
| TLS | Session reports nonempty `Ssl_cipher`; client option file enforces `VERIFY_IDENTITY` |
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
