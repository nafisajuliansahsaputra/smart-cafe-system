/**
 * Synthetic, loopback-only integration acceptance against two independent
 * Spring Boot processes and one disposable PostgreSQL instance.
 *
 * The two processes MUST NOT share JVM memory: cross-replica changes must be
 * observed via committed PostgreSQL NOTIFY and durable cursor recovery.
 */
import { performance } from "node:perf_hooks";
import { execFileSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";

const baseA = "http://127.0.0.1:8080";
const baseB = "http://127.0.0.1:8081";
const tableId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa1111";
const fixturePassword = "e2e-browser-only-secret-2026";
const composeArgs = ["compose", "-f", "compose.yaml", "-f", "infra/perf/compose.replicas.yaml"];
const report = { mode: "synthetic-two-process-recovery", sharedDatabase: true };

function assert(ok, message) {
  if (!ok) throw new Error(message);
}

async function json(url, opts = {}) {
  const response = await fetch(url, { ...opts, signal: AbortSignal.timeout(20000) });
  const data = await response.json().catch(() => null);
  return { status: response.status, data };
}

async function ready(url, retries = 50) {
  for (let i = 0; i < retries; i++) {
    try {
      const v = await json(url + "/api/v1/system/status");
      if (v.status === 200 && v.data?.data?.database === "connected") return;
    } catch { /* API is intentionally not up during stop/restart */ }
    await delay(500);
  }
  throw new Error("Replica did not become healthy");
}

async function login(base, name) {
  const r = await json(base + "/api/v1/staff/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({login:name, password:fixturePassword}),
  });
  assert(r.status === 200 && r.data?.data?.accessToken,
    `Synthetic authentication failed: ${r.status}`);
  return r.data.data.accessToken;
}

async function cursor(base, token, after) {
  const url = new URL(base + "/api/v1/waiter/live-cursor");
  url.searchParams.set("topic","tables");
  if (after !== undefined) url.searchParams.set("after",String(after));
  const began = performance.now();
  const response = await json(url, {
    headers: { Authorization:`Bearer ${token}`, Accept:"application/json" },
  });
  assert(response.status === 200,
    `Authenticated live cursor returned ${response.status}`);
  const value = response.data?.data?.cursor;
  assert(Number.isSafeInteger(value) && value >= 0, "Invalid durable cursor");
  return { value, elapsedMs: Math.round(performance.now()-began) };
}

async function untilCursorAdvanced(base, token, previous) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const seen = await cursor(base, token);
    if (seen.value > previous) return seen;
    await delay(250);
  }
  throw new Error("Durable relay failed to advance cursor");
}

async function mutate(base, token, method, path, body) {
  const response = await json(base + path, {
    method,
    headers: {
      Authorization:`Bearer ${token}`,
      ...(body ? {"Content-Type":"application/json"} : {}),
    },
    ...(body ? {body:JSON.stringify(body)} : {}),
  });
  assert(response.status === 200, `Table mutation returned ${response.status}`);
  return response.data?.data;
}

function compose(command, service) {
  execFileSync("docker", [...composeArgs, command, service], {
    stdio:"pipe", timeout:75_000,
  });
}

// A real cross-replica notification must wake both independent waiters.
await Promise.all([ready(baseA),ready(baseB)]);
const waiterA = await login(baseA,"e2e-waiter");
const cashierA = await login(baseA,"e2e-cashier");
const cashierB = await login(baseB,"e2e-cashier");
const beforeA = await cursor(baseA,cashierA);
const beforeB = await cursor(baseB,cashierB);
assert(beforeA.value === beforeB.value,"Replicas began with inconsistent durable cursors");

const waitA = cursor(baseA,cashierA,beforeA.value);
const waitB = cursor(baseB,cashierB,beforeB.value);
await delay(750);
const opened = await mutate(baseA,waiterA,"POST","/api/v1/waiter/table-visits",
  {tableId});
assert(opened?.id && opened?.joinProof,
  "Waiter open did not return expected synthetic visit");
const [seenA, seenB] = await Promise.all([waitA,waitB]);
assert(seenA.value > beforeA.value && seenB.value > beforeB.value,
  "Committed open event must advance both replica cursors");
report.openEvent = {
  sameDurableCursor:seenA.value === seenB.value,
  backendAElapsedMs:seenA.elapsedMs,
  backendBElapsedMs:seenB.elapsedMs,
};
assert(report.openEvent.sameDurableCursor,
  "Both replicas must converge on the same durable table event");

// While B is STOPPED, A commits a second event. B misses NOTIFY by design.
// After B starts again, its first cursor read MUST recover from PostgreSQL,
// rather than depending on an in-memory wakeup/history.
compose("stop","api_replica");
await mutate(baseA,waiterA,"POST",
  `/api/v1/waiter/table-visits/${opened.id}/join-proof`);
const postRotationA = await untilCursorAdvanced(baseA,cashierA,seenA.value);
assert(postRotationA.value > seenA.value,
  "Live relay did not persist proof-rotation event");
compose("start","api_replica");
await ready(baseB);
const afterRestartB = await cursor(baseB,cashierB);
assert(afterRestartB.value === postRotationA.value,
  "Restarted replica lost durable cursor progress");
report.offlineRecovery = {
  cursorBeforeStop:seenB.value,
  cursorAfterRestart:afterRestartB.value,
  caughtUp:afterRestartB.value > seenB.value,
};

// A third table mutation validates the restarted subscriber's subsequent
// long-poll delivery. Closing an empty test visit is allowed.
const resumed = cursor(baseB,cashierB,afterRestartB.value);
await delay(700);
await mutate(baseA,waiterA,"POST",
  `/api/v1/waiter/table-visits/${opened.id}/close`);
const afterCloseB = await resumed;
assert(afterCloseB.value > afterRestartB.value,
  "Restarted replica did not observe a subsequent committed close event");
report.rejoinedSubscriber = {
  elapsedMs:afterCloseB.elapsedMs, updated:true,
};

// Security: public clients cannot read internal cursors and kitchen has no
// waiter tables permissions, even after restarting a replica.
const unauth = await json(baseB+"/api/v1/waiter/live-cursor?topic=tables");
assert(unauth.status === 401 || unauth.status === 403,
  "Anonymous cursor read must be rejected");
const kitchenB = await login(baseB,"e2e-kitchen");
const forbidden = await json(baseB+"/api/v1/waiter/live-cursor?topic=tables",{
  headers:{Authorization:`Bearer ${kitchenB}`},
});
assert(forbidden.status === 403,"Kitchen must not subscribe to waiter tables");
report.security = {anonymousDenied:true, crossRoleDenied:true};
console.log(JSON.stringify(report));
