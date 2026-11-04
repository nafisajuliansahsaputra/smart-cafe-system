# M6 consolidated integration candidate provenance

## Why a separate review PR exists

Development changes currently span a long, linear chain of stacked draft
pull requests. The review-only `integration/m6-v30-release-candidate`
branch provides one cumulative diff to `main` without editing or merging
the individual PRs. A consolidated diff does **not** authorize deployment,
settlement, real payment processing, branch deletion or a merge.

## Safety contract

`scripts/integration-candidate.mjs` checks:

1. The candidate branch starts with `integration/`.
2. It has exactly one open, same-repository **draft PR to main**.
3. Its full 40-character commit SHA matches exactly **one** other open,
   same-repository draft PR in the actual dependency stack.
4. That source branch has an unbroken, multi-PR ancestry of draft PR
   base/head refs ending at `main`.
5. The current main commit is an ancestor of the exact candidate SHA,
   verified with GitHub compare API using immutable commit SHAs, not
   mutable branch names.
6. The integration-candidate GitHub Actions gate runs
   `--verify-ancestry` automatically, comparing every parent commit SHA
   pair in the stack, not just matching textual base/head branch names.

All reads use the existing least-privilege GitHub Actions token. The script
does not merge, cherry-pick, checkout arbitrary remote code, write files,
create secrets or deploy anything.

A change directly on the consolidated candidate that is **not** also
present in the stacked source causes the gate to fail. This is intentional:
feature fixes should first be submitted as ordinary reviewed, tested
stacked changes and only then copied into the integration candidate.

## CI coverage

`.github/workflows/release-readiness.yml` runs
`scripts/integration-candidate.test.mjs` on **every** PR, testing absent
or mismatched source SHAs, forked candidates, non-draft status, invalid
branch references, duplicate source branches and broken ancestry.

For direct-to-main pull requests whose head begins with `integration/`,
it additionally runs the live read-only GitHub comparison script
**with full commit-by-commit ancestry verification**.
Existing CI, Flyway V27→V30 historical-data rehearsal, Docker,
security, Chromium, CodeQL, OSV and container image scans must still
pass on the same exact HEAD. Successful green workflows do not
substitute for manual code/financial/privacy review.

## Maven OSV when the target main branch is documentation-only

A consolidated candidate PR to `main` is a special dependency-scan case:
the previous reusable PR scanner compared `backend/pom.xml` on both
sides, but the current mainline does not yet contain that file.
That comparison fails before it can evaluate any dependency advisories.

`.github/workflows/osv-maven.yml` now checks the exact immutable PR base
commit for `backend/pom.xml`. If it exists, the normal OSV delta scan
runs. If it does not, the candidate is subject to the **strict full OSV
scan** with `fail-on-vuln: true`. An unavailable feed, invalid
dependency file, or detected known vulnerability fails the gate.
When the app eventually exists on main, scheduled and mainline checks
use the strict full scan. This change must not be interpreted as
suppressing a reported vulnerability or waiving a security check.

## Operational procedure

- Keep PR #61 **draft** and do not merge it.
- Review source PRs from earliest to latest. To make a feature fix,
  update the stacked source PR, not `integration/` directly.
- Fast-forward the candidate only to the same **verified stacked tip
  commit** after tests complete; do not force-push or squash its ancestry.
- Rerun all candidate checks and inspect the updated full diff.
- Before actual merge, configure appropriate protected branch policies
  and get explicit owner approval for the chosen strategy.
- Independently authorize real, isolated staging resources, backup,
  recovery, host TLS, secrets, sandbox verification and operations
  playbooks. Do not treat the local HTTPS edge as public staging.

**This documentation and CI gate are not a deployment or release.**
