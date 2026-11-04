#!/usr/bin/env node
/**
 * Enforce fixable HIGH/CRITICAL CVEs in built runtime images.
 * Summary prints limited package/CVE metadata, never raw scan content.
 * Unfixed findings remain in SARIF and require separate release triage.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function inspectReport(report) {
  if (!report || !Array.isArray(report.Results)) {
    throw new Error("Trivy JSON report is missing the Results array");
  }
  const findings = [];
  for (const result of report.Results) {
    if (result.Vulnerabilities != null && !Array.isArray(result.Vulnerabilities)) {
      throw new Error("Trivy report has malformed Vulnerabilities");
    }
    for (const item of result.Vulnerabilities ?? []) {
      if (!["CRITICAL", "HIGH"].includes(item.Severity)) continue;
      findings.push({
        id: String(item.VulnerabilityID || "UNKNOWN").slice(0, 100),
        severity: item.Severity,
        pkg: String(item.PkgName || "UNKNOWN").slice(0, 100),
        installed: String(item.InstalledVersion || "?").slice(0, 70),
        fixed: String(item.FixedVersion || "").trim().slice(0, 70),
      });
    }
  }
  return {
    findings,
    actionable: findings.filter(x => x.fixed !== ""),
    pending: findings.filter(x => x.fixed === ""),
  };
}

export function formatSummary(image, summary) {
  const heading = "Runtime " + image + ": " + summary.findings.length +
    " HIGH/CRITICAL advisories, " + summary.actionable.length +
    " with published fixes, " + summary.pending.length + " without known fix.";
  const cases = [...new Set(summary.actionable.map(x =>
    x.severity + " " + x.id + " " + x.pkg + " " + x.installed + " -> " + x.fixed
  ))].slice(0, 20);
  return [heading, ...cases.map(x => "FIXABLE: " + x)].join("\n");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const file = process.argv[2];
  if (!file) {
    console.error("Usage: node scripts/inspect-image-vulnerabilities.mjs <trivy-results.json>");
    process.exit(2);
  }
  let summary;
  try {
    summary = inspectReport(JSON.parse(readFileSync(file, "utf8")));
  } catch (error) {
    console.error("FAIL: Trivy scan report is missing or malformed: " + error.message);
    process.exit(2);
  }
  console.log(formatSummary(process.env.SCS_IMAGE_COMPONENT || "unknown", summary));
  if (summary.actionable.length) {
    console.error("FAIL: fixable HIGH/CRITICAL image vulnerabilities; patch image/dependencies without waivers.");
    process.exitCode = 1;
  } else {
    console.log("PASS: no HIGH/CRITICAL image findings with a published fixed version.");
    console.log("LIMIT: unresolved advisories without fixes must be triaged for production.");
  }
}
