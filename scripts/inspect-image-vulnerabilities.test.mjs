import assert from "node:assert/strict";
import test from "node:test";
import { inspectReport, formatSummary } from "./inspect-image-vulnerabilities.mjs";

function cv(id, severity, fixed) {
  return { VulnerabilityID: id, Severity: severity,
    PkgName: "demo-package", InstalledVersion: "1.0", FixedVersion: fixed };
}
test("fixable high and critical OS/library CVEs are release blockers", () => {
  const result = inspectReport({ Results: [
    { Target: "Alpine", Vulnerabilities: [cv("CVE-TEST-OS", "CRITICAL", "1.2")] },
    { Target: "app.jar", Vulnerabilities: [cv("GHSA-TEST-JAR", "HIGH", "2.1")] },
  ] });
  assert.equal(result.actionable.length, 2);
  assert.equal(result.pending.length, 0);
});
test("unfixed high severity findings remain recorded and separate", () => {
  const result = inspectReport({ Results: [
    { Target: "java", Vulnerabilities: [cv("CVE-TEST-NOFIX", "HIGH", "")] },
  ] });
  assert.equal(result.findings.length, 1);
  assert.equal(result.actionable.length, 0);
  assert.equal(result.pending.length, 1);
});
test("low/medium entries do not enter high severity gate", () => {
  const result = inspectReport({ Results: [
    { Target: "node", Vulnerabilities: [cv("CVE-LOW", "LOW", "2")] },
    { Target: "java", Vulnerabilities: [cv("CVE-MED", "MEDIUM", "3")] },
  ] });
  assert.equal(result.findings.length, 0);
});
test("empty scan is valid, missing or malformed scans fail closed", () => {
  assert.equal(inspectReport({ Results: [] }).findings.length, 0);
  assert.throws(() => inspectReport({}), /Results/);
  assert.throws(() => inspectReport({ Results: [{ Vulnerabilities: {} }] }), /Vulnerabilities/);
});
test("summary contains CVE metadata and no raw scanner data", () => {
  const result = inspectReport({ Results: [
    { Target: "image:demo", Vulnerabilities: [cv("CVE-TEST", "HIGH", "4.0")] },
  ] });
  const s = formatSummary("api", result);
  assert.match(s, /CVE-TEST/);
  assert.match(s, /FIXABLE/);
  assert.doesNotMatch(s, /RawContent|PrimaryURL/);
});
