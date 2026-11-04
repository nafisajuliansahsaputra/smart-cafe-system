# M6 isolated staging runtime rehearsal

## What is actually running

`infra/staging/compose.rehearsal.yaml` starts **real Spring Boot,
PostgreSQL 17 and Next.js containers in GitHub Actions** under the
separate Docker Compose project `scs-staging-rehearsal`. This is a
short-lived, locally bound rehearsal in the CI runner, **not an online
staging or production deployment**.

The staging topology does not share containers, Docker networks,
database volumes or fixed credentials with the developer's default
`compose.yaml`.

- `db`: a dedicated named Docker volume and an **internal** data
  network. No DB port is published to the host, LAN or internet.
- `api`: connects only to data and frontend Docker networks, publishes
  only to `127.0.0.1:18080`; read-only filesystem, dropped capabilities,
  `no-new-privileges`, process cap and tmpfs.
- `web`: connects only to frontend network, publishes only to
  `127.0.0.1:13080`, same runtime hardening. It accesses the API via
  private `http://api:8080`.
- Disposable database name `smart_cafe_staging_rehearsal`. Database
  password and API HMAC secret are generated with `openssl rand`,
  placed in a temporary mode-600 envfile outside the checkout, never
  committed or uploaded as an artifact.
- API docs and Swagger UI disabled; `dev-bootstrap` and bootstrap
  username/password disabled. Only QRIS/VA **synthetic sandbox** attempts
  can be created; the sandbox callback webhook key is empty, so it cannot
  accept even a simulated callback in this rehearsal.
- No public host binding, real payments, provider token, production
  accounts, real merchant database, PII, or real-cafe receipt delivery.

The runtime check `scripts/isolated-staging-preflight.mjs` extends
`scripts/staging-preflight.mjs` to require an internal DB network,
frontend/DB network segregation, reserved project and localhost ports,
no published database port, no host-mounted data, no external networks
or volumes, no webhook key, and no unrelated services. Its negative
unit tests run before a Docker image is built.

## Actual CI acceptance

A separate job, `Isolated staging rehearsal / private DB / sandbox /
HTTP smoke`, in `.github/workflows/ci.yml` performs:

1. Generate disposable environment credentials, validate the **resolved**
   Compose JSON before launching any container, and fail closed.
2. Build all three services and perform real Flyway V1–V30 migration
   against a fresh staging-only PostgreSQL volume.
3. Reach the API at `127.0.0.1:18080` and verify a connected database;
   reach the frontend at `127.0.0.1:13080` and verify rendering.
4. Verify API docs and Swagger endpoints are **not publicly accessible**
   (401/403/404; the running Spring Security layer may return 401 before
   the disabled API docs handler can return 404). Unauthorized staff
   order reads must be rejected with HTTP 401/403.
5. Query the **running staging PostgreSQL** to verify successful latest
   Flyway version 30, and verify the DB publishes no host port.
6. Destroy all staging containers, DB volume, temporary env and
   resolved configuration on every runner completion or failure.

Existing CI independently validates cash/browser workflows, fake
digital payments, paid receipts, historical Flyway upgrades, and full
financial-evidence backup/restore. This staging job is additive and
does **not** seed operational staff or live cafe orders.

## Branch protection is a separate, currently open action

The audit on 2026-11-01 found `main` **unprotected**. Available
connected GitHub actions permit read-only inspection and branch/PR
changes but do not expose repository ruleset or branch-protection
configuration. This work does not claim to have changed that setting.

Before merging the release candidate, the owner or authorized repository
admin should configure `Settings → Rules → Rulesets` (or the equivalent
branch protection UI) for `main`, evaluate required reviews suitable
for the repository collaborators, disable force pushes and deletions,
require PR integration, and require up-to-date successful checks,
including the full CI, CodeQL, Maven OSV, Docker CVE and staging preflight
jobs. Verify enforcement with a non-destructive test. A GitHub Free
personal private repository may have ruleset availability/enforcement
limitations depending on plan; confirm in the repository UI rather than
assuming protection.

The full-stack candidate PR #61 and source PR chain remain **draft**.
A passing CI staging rehearsal is not approval for public hosting,
DNS/TLS provisioning, secret management, RPO/RTO, PITR, real QRIS/VA,
real receipt messaging, human financial controls or a real-cafe pilot.

## Additional capability and resource isolation (M6 follow-up)

The resolved-Compose gate rejects cap_add, host namespace/userns settings,
device grants, volumes_from, custom DNS/host overrides, secret mounts and
unexpected security profiles. API and web must retain exactly
no-new-privileges:true, and must never mount Docker volumes. Database
persistence must use exactly one writable staging project database volume
at /var/lib/postgresql/data; data/frontend network names must remain within
the dedicated scs-staging-rehearsal project. Negative Node tests exercise
attempts to bypass cap_drop: ALL and introduce shared volumes/networks.

This policy is for the **disposable localhost-only CI rehearsal**. It is
not proof that a real externally hosted staging environment, its network
perimeter, secrets, disaster recovery, or payment integrations are safe.

## Actual Docker Engine isolation verification (stacked M6 follow-up)

The isolated staging CI job additionally inspects the **running Docker
Engine containers** and network memberships after API/DB/frontend smoke.
A read-only Node inspector rejects missing or foreign containers, unexpected
network attachments/members, a non-internal DB network, DB host ports, public
API/web port binds, unintended volumes, missing rootfs/capability/security
controls, root app users and missing bounded temporary filesystems.

Actual API and web container shell probes verify writable /tmp and the
frontend cache but deny writes to the application image filesystem.
Synthetic negative Node tests cover reused DB volumes and cross-project
network memberships. Docker inspect JSON may contain environment credentials:
it is written with restrictive umask to temporary runner files, never printed
or uploaded, and deleted immediately by an EXIT trap.

This is a CI-only rehearsal verification, **not** an audit of a public staging
host, a production network penetration test, or authorization to use real
customer data, payment credentials or receipt delivery.

## Spring Security generated-password elimination (M6 follow-up)

The backend intentionally uses PostgreSQL-backed staff login and short-lived,
revocable Bearer credentials. Spring Boot previously created an unrelated
in-memory development account because no framework UserDetailsService bean
was defined; it logged a random password during CI staging startup. HTTP
Basic and form login were disabled, but a generated password in logs is an
unnecessary security footgun.

The backend now explicitly defines a fail-closed UserDetailsService that
never resolves a username and prevents auto-creation of the framework user.
Staff login and Bearer authorization remain unchanged. A PostgreSQL-backed
integration test rejects both a staff username and the conventional generated
framework username through this service, refuses HTTP Basic even with a
real synthetic staff password, and verifies JSON staff login still works.

The isolated staging CI reads API logs into a runner-private temporary file
and **fails** if a generated-password/in-memory-user startup signature
appears. The file is deleted via an EXIT trap and is not printed or uploaded.
This does not imply that production credential lifecycle, MFA, account
recovery or public TLS protection is complete.

## Development ADMIN bootstrap pre-migration guard (stacked draft PR #69)

When `dev-bootstrap` is explicitly enabled, a dedicated
`BeanFactoryPostProcessor` validates the exact active profile set, the
required local acknowledgement, resolved JDBC URL and effective sandbox/
merchant payment properties **before normal singleton creation, including
Flyway migration initialization**. Invalid/remote database destinations,
mixed profiles or payment settings fail closed before the application
attempts migration or first-ADMIN provisioning. A unit test proves that a
simulated ordinary singleton is never initialized after an unsafe setting.

The earlier first-ADMIN runner guard remains and independently checks the
**actual database connection metadata** before account insertion, which
protects against differing configuration/driver resolution. These checks
are specific to the opt-in local bootstrap profile. They do **not** claim
that the `db` hostname is physically private, that unrelated normal
application profiles cannot be misconfigured, or that any real database
can safely be selected for this dev-only process. External hosted staging,
production administrator recovery and payment integration remain blocked.
