#!/usr/bin/env node
import { pathToFileURL } from "node:url";

/** Check a single, same-repository, open-PR ancestry chain by base/head refs. */
export function inspectReleaseChain(pulls, tipBranch, repoFullName, main = "main") {
  if (!Array.isArray(pulls) || pulls.length === 0) {
    throw new Error("No open pull requests were returned");
  }
  if (!tipBranch || typeof tipBranch !== "string") {
    throw new Error("A tip branch must be specified");
  }
  const refs = new Map();
  for (const pr of pulls) {
    if (!pr || typeof pr.number !== "number" || !pr.head?.ref ||
        !pr.base?.ref || !pr.head?.sha || !pr.head?.repo?.full_name ||
        !pr.base?.repo?.full_name) {
      throw new Error("Incomplete pull request record");
    }
    if (pr.head.repo.full_name !== repoFullName ||
        pr.base.repo.full_name !== repoFullName) {
      // Unrelated external PRs are out of scope; fail if they enter the chain.
      continue;
    }
    if (refs.has(pr.head.ref)) throw new Error("Duplicate open PR head ref");
    refs.set(pr.head.ref, pr);
  }
  const seen = new Set();
  const reversed = [];
  let ref = tipBranch;
  while (ref !== main) {
    if (seen.has(ref)) throw new Error("Cycle in stacked PR base refs");
    seen.add(ref);
    const pr = refs.get(ref);
    if (!pr) throw new Error("Missing same-repo open PR for chain ref: " + ref);
    if (pr.base.ref === pr.head.ref) throw new Error("Self-based PR: " + ref);
    reversed.push({
      number: pr.number, headRef: pr.head.ref, headSha: pr.head.sha,
      baseRef: pr.base.ref, draft: pr.draft === true,
    });
    if (reversed.length > 200) throw new Error("PR stack exceeds safety limit");
    ref = pr.base.ref;
  }
  const chain = reversed.reverse();
  if (chain.length === 0) throw new Error("Tip must be an open PR branch");
  const included = new Set(chain.map(p => p.headRef));
  return {
    root: chain[0].number,
    tip: chain.at(-1).number,
    chain,
    otherOpenPrCount: pulls.length - chain.length,
    excludedRefs: [...refs.keys()].filter(ref => !included.has(ref)),
  };
}

async function github(path, token) {
  const reply = await fetch("https://api.github.com" + path, {
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: "Bearer " + token,
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "scs-release-readiness",
    },
    signal: AbortSignal.timeout(15000),
  });
  if (!reply.ok) throw new Error("GitHub returned HTTP " + reply.status);
  return reply.json();
}

export async function fetchOpenPulls(repo, token) {
  const all = [];
  for (let page = 1; page <= 5; page++) {
    const current = await github(
      "/repos/" + repo + "/pulls?state=open&per_page=100&page=" + page, token);
    if (!Array.isArray(current)) throw new Error("Invalid GitHub PR response");
    all.push(...current);
    if (current.length < 100) return all;
  }
  throw new Error("More than 500 open PRs; manual audit required");
}

/**
 * Optional stronger Git DAG verification (one compare request per PR).
 * Ahead with behind_by=0 proves each PR's base head is its ancestor.
 */
export async function verifyGitAncestry(repo, token, chain) {
  // Compare immutable commit SHA pairs rather than URL-encoded branch paths.
  // This also avoids a forced branch update silently changing a comparison.
  const mainRef = await github("/repos/" + repo + "/branches/main", token);
  let baseSha = mainRef?.commit?.sha;
  if (!/^[a-f0-9]{40}$/.test(baseSha ?? "")) {
    throw new Error("Unable to resolve main commit for git ancestry validation");
  }
  for (const item of chain) {
    if (!/^[a-f0-9]{40}$/.test(item.headSha)) {
      throw new Error("Invalid PR commit SHA at #" + item.number);
    }
    const status = await github(
      "/repos/" + repo + "/compare/" + baseSha + "..." + item.headSha, token);
    if (status.status !== "ahead" || status.behind_by !== 0 ||
        !Number.isInteger(status.ahead_by) || status.ahead_by <= 0) {
      throw new Error("Git ancestry diverged at PR #" + item.number);
    }
    baseSha = item.headSha;
  }
}

async function main() {
  const repo = process.env.GITHUB_REPOSITORY;
  const token = process.env.GITHUB_TOKEN;
  const tip = process.env.SCS_RELEASE_TIP_BRANCH;
  if (!repo || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo) ||
      !token || !tip) {
    throw new Error("GITHUB_REPOSITORY, GITHUB_TOKEN and SCS_RELEASE_TIP_BRANCH are required");
  }
  const pulls = await fetchOpenPulls(repo,token);
  const result = inspectReleaseChain(pulls,tip,repo);
  if (process.argv.includes("--verify-ancestry")) {
    await verifyGitAncestry(repo,token,result.chain);
  }
  const lines = result.chain.map(p =>
    "#" + p.number + " " + p.headRef + " -> " + p.baseRef +
    " (" + p.headSha.slice(0,8) + ")");
  process.stdout.write(
    "STACK CHECK PASSED: " + result.chain.length + " same-repository PRs, " +
    "root #" + result.root + ", tip #" + result.tip +
    ", other open PRs " + result.otherOpenPrCount + "\n" +
    lines.join("\n") + "\n" +
    (process.argv.includes("--verify-ancestry")
      ? "Git commit ancestry: verified for every link.\n"
      : "Git commit ancestry: not checked (add --verify-ancestry).\n"));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(e => {
    process.stderr.write("STACK CHECK FAILED: " + e.message + "\n");
    process.exitCode = 1;
  });
}
