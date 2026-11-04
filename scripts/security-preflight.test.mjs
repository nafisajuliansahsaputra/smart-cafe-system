import assert from "node:assert/strict";
import test from "node:test";
import { scanText } from "./security-preflight.mjs";

// Generate fake token-shaped samples at runtime rather than committing them.
test("recognizes generated GitHub PAT without revealing its contents", () => {
  const token = "ghp_" + "a".repeat(36);
  assert.deepEqual(
    scanText("apps/web/lib/server.ts", "const key = '" + token + "';")
      .map(({ rule }) => rule),
    ["github-classic-token"]
  );
});
test("recognizes an AWS access key and a private key header", () => {
  const text = "AKIA" + "A".repeat(16) + "\n" +
    "-----BEGIN " + "PRIVATE KEY-----";
  assert.deepEqual(
    scanText("backend/src/main/resources/application.yml", text)
      .map(({ rule }) => rule),
    ["private-key", "aws-access-key-id"]
  );
});
test("detects browser token storage and dangerous HTML injection", () => {
  const sample = "window." + "localStorage.setItem('token', accessToken);" +
    "\n" + "return <div " + "dangerouslySetInnerHTML={{__html: content}} />;";
  assert.deepEqual(
    scanText("apps/web/app/(staff)/staff/page.tsx", sample)
      .map(({ rule }) => rule),
    ["browser-token-storage", "html-injection-sink"]
  );
});
test("does not apply browser sink restrictions to tests and docs", () => {
  const text = "window." + "localStorage.getItem('x')";
  assert.equal(scanText("apps/web/e2e/security.spec.mjs", text).length, 0);
  assert.equal(scanText("docs/SECURITY.md", text).length, 0);
});
test("recognizes no secret in documented disposable environment variables", () => {
  const sample = "POSTGRES_PASSWORD=ci-not-for-production\n" +
    "SCS_JOIN_THROTTLE_SECRET=edge-ci-only-disposable-hmac-secret";
  assert.deepEqual(scanText(".github/workflows/ci.yml", sample), []);
});
test("never returns credential values, only path, line and rule", () => {
  const secret = "sk_" + "live_" + "K".repeat(32);
  const issues = scanText("private-config.env", "first line\n" + secret);
  assert.deepEqual(issues, [{ path: "private-config.env", line: 2, rule: "stripe-secret-key" }]);
  assert.equal(JSON.stringify(issues).includes(secret), false);
});
