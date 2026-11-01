#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const PROJECT = "scs-staging-rehearsal";
const netNames = {
  data: PROJECT + "_data",
  frontend: PROJECT + "_frontend"
};
const expectedNetworkMembers = {
  data: ["api", "db"],
  frontend: ["api", "web"]
};
const expectedServiceNetworks = {
  db: ["data"],
  api: ["data", "frontend"],
  web: ["frontend"]
};

/** Check effective Docker Engine state, not only the resolved Compose plan.
 * Input must be sanitized before it is ever written to public CI logs:
 * docker inspect includes secret environment variables.
 */
export function inspectStagingRuntime(containers, networks) {
  const issues = [];
  const reject = issue => issues.push(issue);
  if (!Array.isArray(containers) || !Array.isArray(networks)) {
    return ["Docker container and network inspect arrays are required"];
  }
  const services = new Map();
  for (const c of containers) {
    const labels = c?.Config?.Labels ?? {};
    const service = labels["com.docker.compose.service"];
    if (!["db", "api", "web"].includes(service) ||
        labels["com.docker.compose.project"] !== PROJECT ||
        services.has(service)) {
      reject("Unexpected, duplicate or foreign staging container");
      continue;
    }
    services.set(service, c);
  }
  if (containers.length !== 3 || services.size !== 3) {
    reject("Exactly three isolated staging containers are required");
  }
  for (const service of ["db", "api", "web"]) {
    const c = services.get(service);
    if (!c) continue;
    const host = c.HostConfig ?? {};
    const config = c.Config ?? {};
    if (c.State?.Running !== true) reject(service + ": container is not running");
    if (host.Privileged !== false ||
        host.NetworkMode === "host" ||
        ["host", "container"].some(x => String(host.PidMode ?? "").startsWith(x)) ||
        ["host", "container"].some(x => String(host.IpcMode ?? "").startsWith(x)) ||
        host.UsernsMode === "host" ||
        (host.CapAdd ?? []).length > 0 ||
        (host.Devices ?? []).length > 0 ||
        (host.Binds ?? []).length > 0 ||
        (host.VolumesFrom ?? []).length > 0) {
      reject(service + ": forbidden host privilege or attachment");
    }
    const actualNetworks = Object.keys(c.NetworkSettings?.Networks ?? {}).sort();
    const expectedNetworks = expectedServiceNetworks[service].map(x => netNames[x]).sort();
    if (actualNetworks.join(",") !== expectedNetworks.join(",")) {
      reject(service + ": unexpected runtime network attachment");
    }
    const mounts = c.Mounts ?? [];
    const ports = host.PortBindings ?? {};
    if (service === "db") {
      if (Object.keys(ports).length > 0) reject("db: published host port");
      if (mounts.length !== 1 ||
          mounts[0]?.Type !== "volume" ||
          mounts[0]?.Name !== PROJECT + "_staging_postgres_data" ||
          mounts[0]?.Destination !== "/var/lib/postgresql/data" ||
          mounts[0]?.RW !== true) {
        reject("db: requires only dedicated writable PostgreSQL volume");
      }
      continue;
    }
    const target = service === "api" ? "8080/tcp" : "3000/tcp";
    const published = service === "api" ? "18080" : "13080";
    const binds = ports[target];
    if (Object.keys(ports).length !== 1 ||
        !Array.isArray(binds) || binds.length !== 1 ||
        binds[0]?.HostIp !== "127.0.0.1" ||
        binds[0]?.HostPort !== published) {
      reject(service + ": runtime port is not restricted to loopback");
    }
    if (host.ReadonlyRootfs !== true ||
        host.PidsLimit !== 512 ||
        !Array.isArray(host.CapDrop) ||
        !host.CapDrop.includes("ALL") ||
        !Array.isArray(host.SecurityOpt) ||
        host.SecurityOpt.length !== 1 ||
        !String(host.SecurityOpt[0]).startsWith("no-new-privileges")) {
      reject(service + ": runtime privilege hardening is missing");
    }
    if (config.User !== (service === "api" ? "cafe" : "node")) {
      reject(service + ": expected unprivileged container user");
    }
    if (mounts.length !== 0) reject(service + ": unexpected host or volume mount");
    const tmpfs = host.Tmpfs ?? {};
    for (const targetPath of service === "api"
      ? ["/tmp"] : ["/tmp", "/app/.next/cache"]) {
      const opts = tmpfs[targetPath];
      if (typeof opts !== "string" || !opts.includes("nosuid") ||
          !opts.includes("nodev")) {
        reject(service + ": missing bounded temporary filesystem " + targetPath);
      }
    }
  }
  const foundNetworks = new Map(networks.map(n => [n?.Name, n]));
  if (networks.length !== 2 || foundNetworks.size !== 2) {
    reject("Exactly two staging networks are required");
  }
  for (const [short, full] of Object.entries(netNames)) {
    const net = foundNetworks.get(full);
    if (!net) {
      reject(short + ": missing runtime network");
      continue;
    }
    if (net.Labels?.["com.docker.compose.project"] !== PROJECT ||
        net.Internal !== (short === "data")) {
      reject(short + ": foreign or unsafe Docker network");
    }
    const actualMembers = Object.keys(net.Containers ?? {}).sort();
    const expectedMembers = expectedNetworkMembers[short]
      .map(name => services.get(name)?.Id).sort();
    if (expectedMembers.some(x => !x) ||
        actualMembers.join(",") !== expectedMembers.join(",")) {
      reject(short + ": unexpected connected containers");
    }
  }
  return issues;
}

async function main() {
  if (process.argv.length !== 4) {
    throw new Error("Usage: node scripts/isolated-staging-runtime-inspect.mjs <docker-inspect.json> <network-inspect.json>");
  }
  const containers = JSON.parse(await readFile(process.argv[2], "utf8"));
  const networks = JSON.parse(await readFile(process.argv[3], "utf8"));
  const violations = inspectStagingRuntime(containers, networks);
  if (violations.length) throw new Error(violations.join("; "));
  process.stdout.write("PASS: running staging containers, runtime privileges, loopback port bindings, isolated volume and network memberships verified.\n");
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(e => {
    process.stderr.write("STAGING ENGINE VERIFICATION FAILED: " + e.message + "\n");
    process.exitCode = 1;
  });
}
