#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

/**
 * Guard only the *disposable, loopback-bound* synthetic rehearsal topology.
 * It is not a deploy command and never validates real provider connectivity.
 */
export function inspectSyntheticStagingConfig(config) {
  const violations = [];
  const reject = message => violations.push(message);
  if (!config || typeof config !== "object" || !config.services ||
      typeof config.services !== "object") {
    throw new Error("Docker Compose JSON with services is required");
  }
  const services = config.services;
  for (const name of ["db", "api", "web"]) {
    if (!services[name]) reject("Missing service: " + name);
  }
  if (violations.length) return violations;
  for (const [name, service] of Object.entries(services)) {
    if (service.privileged === true) reject(name + ": privileged mode forbidden");
    if (service.network_mode === "host") reject(name + ": host networking forbidden");
    if (service.pid === "host" || service.ipc === "host") {
      reject(name + ": host namespace forbidden");
    }
    for (const port of service.ports ?? []) {
      if (!port || port.host_ip !== "127.0.0.1") {
        reject(name + ": published ports must bind only to 127.0.0.1");
      }
    }
  }
  for (const name of ["api","web"]) {
    const service = services[name];
    if (service.read_only !== true) reject(name + ": read-only rootfs required");
    if (!service.cap_drop?.includes("ALL")) reject(name + ": cap_drop ALL required");
    if (!service.security_opt?.some(x=>String(x).startsWith("no-new-privileges:"))) {
      reject(name + ": no-new-privileges required");
    }
    if (typeof service.pids_limit !== "number" || service.pids_limit <= 0) {
      reject(name + ": process limit required");
    }
    const env = service.environment ?? {};
    if (env.SCS_PAYMENT_ENVIRONMENT !== "sandbox" ||
        env.SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED !== "true") {
      reject(name + ": only fake sandbox checkout is authorized");
    }
    if (String(env.SPRING_PROFILES_ACTIVE ?? "").includes("dev-bootstrap") ||
        String(env.SCS_BOOTSTRAP_USERNAME ?? "").length > 0 ||
        String(env.SCS_BOOTSTRAP_PASSWORD ?? "").length > 0) {
      reject(name + ": bootstrap credentials/profile forbidden");
    }
  }
  const db = services.db.environment ?? {};
  const api = services.api.environment ?? {};
  const web = services.web.environment ?? {};
  if (!/^smart_cafe_staging_[a-z0-9_]+$/.test(String(db.POSTGRES_DB ?? ""))) {
    reject("Database name must be a clearly disposable staging rehearsal DB");
  }
  if (typeof db.POSTGRES_PASSWORD !== "string" ||
      db.POSTGRES_PASSWORD.length < 24 ||
      db.POSTGRES_PASSWORD.includes("replace-with")) {
    reject("Database credential is missing or placeholder");
  }
  if (!db.POSTGRES_USER || api.DB_USERNAME !== db.POSTGRES_USER ||
      api.DB_PASSWORD !== db.POSTGRES_PASSWORD ||
      api.DB_URL !== "jdbc:postgresql://db:5432/" + db.POSTGRES_DB) {
    reject("API must use the isolated Compose DB only");
  }
  if (api.API_DOCS_ENABLED !== "false") {
    reject("Public API docs must be disabled in staging");
  }
  if (typeof api.SCS_JOIN_THROTTLE_SECRET !== "string" ||
      api.SCS_JOIN_THROTTLE_SECRET.length < 32 ||
      api.SCS_JOIN_THROTTLE_SECRET.startsWith("replace")) {
    reject("Guest-join secret is missing or placeholder");
  }
  if (web.API_INTERNAL_URL !== "http://api:8080" ||
      web.NODE_ENV !== "production") {
    reject("Web must use only its private API and production runtime mode");
  }
  return violations;
}

async function main() {
  if (process.argv.length !== 3) {
    throw new Error("Usage: node scripts/staging-preflight.mjs <docker-compose-config.json>");
  }
  const config = JSON.parse(await readFile(process.argv[2],"utf8"));
  const issues = inspectSyntheticStagingConfig(config);
  if (issues.length) {
    throw new Error(issues.join("; "));
  }
  process.stdout.write("SYNTHETIC STAGING PREFLIGHT PASSED: loopback only, " +
    "private DB, non-privileged app, fake sandbox, no bootstrap/docs.\n" +
    "No remote staging, live merchant, public DNS or payment deployment performed.\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(e => {
    process.stderr.write("SYNTHETIC STAGING PREFLIGHT FAILED: " + e.message + "\n");
    process.exitCode = 1;
  });
}
