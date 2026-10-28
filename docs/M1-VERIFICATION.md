# M1 scaffold: verification record

Status: **M1 executable scaffold verified by GitHub Actions on feature branch; draft PR awaits review and merge.**

## Architecture and security boundaries

- One Next.js application and one Spring Boot modular-monolith skeleton.
- Runtime modules created: common API response/error utilities, identity security, system probe, and orders integer-money utility. Other modules remain future M2/M3/M4 work.
- Public GET /api/v1/system/status probes the PostgreSQL schema; no order, financial or staff business routes are enabled.
- Spring Security denies all other endpoints by default; default CSRF remains enabled.
- Flyway baseline includes unique active-visit index, composite guest/visit ownership FK, IDR nonnegative checks, staff role references, immutable-order snapshot columns, audit/outbox tables.
- No staff logins, guest sessions, payment confirmation, cash capture, checkout, messaging, or actual OCC exists yet. No test data is automatically inserted outside fixed staff role definitions.

## Local startup (requires Docker Engine with Compose)

1. Copy .env.example to .env, replace the local-only database password.
2. Run: docker compose up --build
3. Open http://localhost:3000. This page calls the backend from Next.js server-side.
4. Check http://localhost:8080/api/v1/system/status. It should show data.database=connected.
5. Check http://localhost:8080/actuator/health (DB readiness when enabled).
6. Optionally run docker compose exec -T db psql -U smart_cafe -d smart_cafe < infra/fixtures/demo-menu.sql with matching customized DB user/name; fixtures are manual.
7. Optional local API docs (only when API_DOCS_ENABLED=true): http://localhost:8080/swagger-ui.html.
8. Stop containers with docker compose down (do not use down -v unless you intend to delete local data).

## Tests

- Java tests: cd backend && mvn -B -ntp verify (Docker required for Testcontainers).
- Frontend: cd apps/web && npm ci && npm run lint && npm run typecheck && npm run build.
- Pull-request CI executes both jobs with Java 21, Node 22 and Docker-enabled Testcontainers.

## Evidence policy

CI evidence: https://github.com/nafisajuliansahsaputra/smart-cafe-system/actions/runs/40739664242 (all three jobs successful). A clean GitHub-hosted Ubuntu checkout built and launched all Docker Compose services, applied Flyway, returned the DB-backed status, exercised the OpenAPI endpoint and staff deny envelope, and rendered the Next.js page with connected state. Java unit and PostgreSQL Testcontainers tests and Next.js npm ci/lint/typecheck/build also passed. This evidence is limited to the CI environment, not an assertion of deployment or a Windows-local smoke.

Known open tasks: full module API boundaries, login/guest session, staff OCC, and actual order transitions (M2). CI/Compose evidence is tracked through pull request checks; no production deployment is claimed.
