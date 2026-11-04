# M2 disposable HTTPS edge acceptance

**Status:** PR #10, stacked after PR #9; local/CI **only**, not a production-ready public ingress or deployment.

## What the overlay actually does

- Compose overlay `infra/edge/compose.edge.yaml` adds Nginx listening on **127.0.0.1:8443** (not on a public interface). Next.js and the API retain their existing 127.0.0.1 local host-port bindings. Only the `web` service is proxied; the Spring Boot backend is never public-facing through this edge.
- `infra/edge/nginx.conf` accepts the literal local test hostname `localhost` and returns **421** for other Hosts. It terminates TLS with a local throwaway self-signed certificate and permits TLS 1.2/1.3. A real hostname and trusted certificate require a separate reviewed configuration.
- The Nginx proxy sets Host and `X-Forwarded-Host` to the **validated client Host** and `X-Forwarded-Proto` to `https`. It overwrites `X-Forwarded-For` and `X-Real-IP` with the direct TCP peer and removes `Forwarded`. It never appends user-supplied forwarded headers.
- Per-TCP-peer fixed token-bucket ingress controls apply to **POST** on `/t/*` and `/staff/login` with independent zones (2 requests/second, burst 6). Empty keys on GET bypass these counters. Maximum ingress request size is 128KB; Next.js Server Actions independently enforce a stricter 32KB limit. These controls are additional to PostgreSQL guest join throttling and staff-account login throttling.
- No HSTS preloading or real-domain security claim is made for self-signed localhost certificates. In production, issuing a real certificate, hosting, network topology, trusted CDN IP ranges, Host/SNI behavior, CSP/HSTS, edge DDoS protection and ingress logs/redaction require independent review.

## Running the local-only overlay

Use a **disposable database and synthetic staff fixture only**. Set `POSTGRES_*` and `SCS_JOIN_THROTTLE_SECRET` in gitignored `.env`. Generate a throwaway cert outside git:

```sh
mkdir -p infra/edge/local-tls
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 \
  -keyout infra/edge/local-tls/key.pem \
  -out infra/edge/local-tls/cert.pem \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost'
docker compose -f compose.yaml -f infra/edge/compose.edge.yaml up --build --detach
curl --insecure https://localhost:8443/staff/login
docker compose -f compose.yaml -f infra/edge/compose.edge.yaml down --volumes --remove-orphans
rm -rf infra/edge/local-tls
```

**Never** copy the self-signed certificate or synthetic fixture to a public server. `--insecure` is for this isolated certificate only. Do not use `down --volumes` on a database you need to preserve.

## CI verification

An additional, independent `edge-https-e2e` GitHub Actions job builds isolated services, generates its own 1-day certificate, verifies the HTTPS landing page, rejects an unknown Host (421), sends rapid POSTs with **forged different X-Forwarded-For values** and demands an edge 429, then seeds the synthetic E2E table/users and runs all existing Chromium cash, role, CSRF and negative tests over `https://localhost:8443`. An edge-only browser test forges X-Forwarded-Host, X-Forwarded-Proto and X-Forwarded-For on the authenticated login POST; successful login and HttpOnly/Secure/Strict cookie verify forwarded-header rewriting in the full browser path. CI always tears down disposable Postgres and TLS secrets. Check exact-head CI, never infer passing status from tests merely existing.

## Security boundaries and open gates

- The direct TCP peer is valid for this *single-hop* local proxy. **If a CDN or external load balancer is added**, Nginx will see its address and all guests may share a single rate-limit bucket. Before multi-hop deployment, allowlist real upstream proxy IPs, evaluate a trusted `real_ip` chain and ensure headers are overwritten at the perimeter. Never trust an arbitrary X-Forwarded-For supplied by the client.
- Explicit localhost-only Host restriction means this overlay is **not a turnkey staging/prod deployment configuration**. No public ingress or domain is configured.
- In-flight payments and recovery from network disconnection, real TLS certificate renewal, CSP and HSTS, mobile accessibility and production cash/tax/receipt policy are not solved by this slice.
