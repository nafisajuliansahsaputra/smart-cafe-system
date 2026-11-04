// Node 22 native-fetch; runs ONLY against a disposable locally bound API.
import { performance } from "node:perf_hooks";

const endpoint = process.env.SCS_PERF_API_URL ?? "http://127.0.0.1:8080";
const expectedGlobal = Number(process.env.SCS_PERF_GLOBAL_LIMIT ?? 12);
const expectedStaff = Number(process.env.SCS_PERF_STAFF_LIMIT ?? 3);
const users = Number(process.env.SCS_PERF_IDENTITIES ?? 12);
const slotsPerUser = expectedStaff;
const testPassword = "perf-live-ci-only-2026-secret";
const auth = [];
const timestamp = new Date().toISOString();

if (!/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(endpoint)) {
  throw new Error("Refusing to run load fixture against non-loopback or HTTPS endpoint");
}
if (!Number.isInteger(users) || users < 2 || users > 16 ||
    expectedGlobal < 1 || expectedGlobal > 48 ||
    expectedStaff < 1 || expectedStaff > 6 ||
    users * slotsPerUser <= expectedGlobal) {
  throw new Error("Invalid synthetic load-test limits");
}

async function login(n) {
  const name = `perf-live-cashier-${String(n).padStart(2,"0")}`;
  const response = await fetch(`${endpoint}/api/v1/staff/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ login: name, password: testPassword }),
    signal: AbortSignal.timeout(12_000),
  });
  const body = await response.json();
  if (response.status !== 200 || !body.data?.accessToken) {
    throw new Error(`Synthetic login failed: status ${response.status}`);
  }
  return body.data.accessToken;
}

function percentile(values, q) {
  if (!values.length) return null;
  const sorted = [...values].sort((a,b)=>a-b);
  return Math.round(sorted[Math.ceil(sorted.length * q)-1]);
}

async function longPoll(token) {
  const began = performance.now();
  const response = await fetch(
    `${endpoint}/api/v1/ops/live-cursor?topic=orders&after=0`, {
      headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
      signal: AbortSignal.timeout(26_000),
    });
  const result = await response.json().catch(()=>null);
  const elapsedMs = Math.round(performance.now() - began);
  if (response.status === 200 && !Number.isSafeInteger(result?.data?.cursor)) {
    throw new Error("Long poll returned 200 without numeric cursor");
  }
  if (![200,429].includes(response.status)) {
    throw new Error(`Live load unexpected HTTP ${response.status}`);
  }
  return { status: response.status, elapsedMs };
}

auth.push(...await Promise.all(Array.from({length:users},(_,n)=>login(n+1))));
const started = performance.now();
const pending = [];
for (const token of auth) for (let i=0;i<slotsPerUser;i++) pending.push(longPoll(token));

// Verify the API health path stays responsive while long polls are active.
await new Promise(resolve=>setTimeout(resolve, 700));
const healthStart = performance.now();
const healthResponse = await fetch(`${endpoint}/actuator/health`, {
  signal: AbortSignal.timeout(8_000),
});
const healthElapsedMs = Math.round(performance.now()-healthStart);
const healthPayload = await healthResponse.json();
if (healthResponse.status !== 200 || healthPayload.status !== "UP") {
  throw new Error("API unhealthy while long polls are pending");
}

const samples = await Promise.all(pending);
const accepted = samples.filter(x=>x.status===200);
const rejected = samples.filter(x=>x.status===429);
const summary = {
  observedAt:timestamp, mode:"isolated-ci-synthetic",
  clients:users, requests:samples.length,
  configuredGlobal:expectedGlobal, configuredPerStaff:expectedStaff,
  accepted:accepted.length, rejected:rejected.length,
  acceptedP50Ms:percentile(accepted.map(x=>x.elapsedMs),.5),
  acceptedP95Ms:percentile(accepted.map(x=>x.elapsedMs),.95),
  rejectedP95Ms:percentile(rejected.map(x=>x.elapsedMs),.95),
  healthWhileWaitingMs:healthElapsedMs,
  wallMs:Math.round(performance.now()-started),
};
console.log(JSON.stringify(summary));
if (rejected.length < 1 || accepted.length < expectedGlobal-2 ||
    healthElapsedMs > 7000 || summary.wallMs > 27000) {
  throw new Error("Concurrent cursor acceptance / admission / health gate failed");
}

// A single credential cannot starve independent staff, even when its own
// requests exceed the per-principal cap. Repeat with a fresh wave.
const single = [...Array.from({length:expectedStaff+4},()=>longPoll(auth[0])),
                longPoll(auth[1])];
const scoped = await Promise.all(single);
const soloDenied = scoped.slice(0,-1).filter(x=>x.status===429).length;
const otherAccepted = scoped.at(-1).status===200;
console.log(JSON.stringify({
  mode:"per-principal-fairness", singleRequests:expectedStaff+4,
  singleRejected:soloDenied, secondPrincipalAccepted:otherAccepted,
}));
if (soloDenied < 1 || !otherAccepted) {
  throw new Error("Single credential monopolized polling admission");
}
