#!/usr/bin/env node
/**
 * High-confidence HEAD-tree leak and browser-security preflight.
 * This does NOT replace full Git history secret scanning, CodeQL/SAST,
 * Java dependency advisories or real secret rotation.
 * Reports file:line:rule only, never echoes the matching secret.
 */
import { execFileSync } from "node:child_process";
import { lstatSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const credentialRules = Object.freeze([
  ["private-key", /-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/g],
  ["github-classic-token", /gh[pousr]_[A-Za-z0-9]{36,}/g],
  ["github-fine-grained-token", /github_pat_[A-Za-z0-9_]{80,}/g],
  ["aws-access-key-id", /(?:AKIA|ASIA)[A-Z0-9]{16}/g],
  ["google-api-key", /AIza[0-9A-Za-z_-]{35}/g],
  ["gitlab-access-token", /glpat-[0-9A-Za-z_-]{20,}/g],
  ["stripe-secret-key", /sk_(?:live|test)_[0-9A-Za-z]{20,}/g],
  ["openai-api-key", /sk-(?:proj-)?[0-9A-Za-z_-]{36,}/g],
  ["slack-token", /xox[baprs]-[0-9]{10,}-[0-9]{10,}-[0-9A-Za-z]{20,}/g],
]);
const frontendRules = Object.freeze([
  ["browser-token-storage", /\b(?:window\.)?(?:localStorage|sessionStorage)\s*(?:\.|\[)/g],
  ["html-injection-sink", /\bdangerouslySetInnerHTML\s*=/g],
  ["dynamic-code-eval", /\beval\s*\(/g],
  ["dynamic-function-constructor", /\bnew\s+Function\s*\(/g],
]);
function frontendSource(path) {
  return /^apps\/web\/(?:app|components|lib)\//.test(path) &&
    /\.(?:js|jsx|ts|tsx)$/.test(path);
}
export function scanText(path, text) {
  const issues = [];
  const rules = frontendSource(path)
    ? credentialRules.concat(frontendRules) : credentialRules;
  for (const [rule, regexp] of rules) {
    for (const match of text.matchAll(regexp)) {
      const line = text.slice(0, match.index).split("\n").length;
      issues.push({ path, line, rule });
    }
  }
  return issues;
}
export function scanTrackedTree(directory = root) {
  const tracked = execFileSync("git", ["ls-files", "-z"], {
    cwd: directory, maxBuffer: 64 * 1024 * 1024,
  }).toString("utf8").split("\0").filter(Boolean);
  const issues = [];
  let scanned = 0;
  for (const path of tracked) {
    const full = resolve(directory, path);
    const stat = lstatSync(full);
    if (!stat.isFile()) continue;
    const bytes = readFileSync(full);
    if (bytes.includes(0)) continue; // binary files aren't source text
    scanned += 1;
    issues.push(...scanText(path.replaceAll("\\", "/"), bytes.toString("utf8")));
  }
  return { scanned, issues };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { scanned, issues } = scanTrackedTree();
  for (const issue of issues) {
    // Never print matching secret values or surrounding lines.
    console.error(issue.path + ":" + issue.line + ": " + issue.rule);
  }
  if (issues.length) {
    console.error("FAIL: " + issues.length + " credential or browser sink findings. Rotate exposed real secrets before changing code.");
    process.exitCode = 1;
  } else {
    console.log("PASS: " + scanned + " tracked text files screened for high-confidence credential patterns and restricted browser sinks.");
    console.log("LIMIT: no Git history, untracked files, binary content, Java CVE advisories, or dynamic data-flow analysis.");
  }
}
