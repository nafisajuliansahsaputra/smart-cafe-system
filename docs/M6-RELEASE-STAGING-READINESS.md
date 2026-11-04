# M6 release stack and synthetic staging preflight (draft PR #59)

## Repository reality

As checked on 2026-11-01, all 57 open draft PRs from #1 through #58 form
one linear stacked chain from the M1 executable scaffold to the manager
cash variance investigation at #58. **None of that branch stack is merged
to main, and nothing is deployed.** The main branch cannot be assumed to
contain the current working application. A CI-green branch is not a
released deployment.

The release verification introduced here **does not merge**, rebase,
push, retarget branches, create cloud resources, publish images, handle
merchant keys, seed real customers, or deploy anything.

## New checks

- `scripts/release-chain.mjs` queries GitHub's open PR metadata using an
  ephemeral, read-only GITHUB_TOKEN and `SCS_RELEASE_TIP_BRANCH`.
  It traverses each `head.ref -> base.ref` to `main`; unknown bases,
  cycles, duplicate heads, incomplete metadata, forks within the chain,
  or an excessive stack fail. The script reports ordered PR numbers,
  head SHA prefixes and side branches for human review.
- Basic mode validates **PR reference topology**, not underlying commit
  ancestry. Explicit `--verify-ancestry` performs a read-only
  compare of every base/head branch pair and rejects diverged Git
  histories. This can involve dozens of GitHub API calls, so it runs
  only on an explicitly selected manual workflow.
- `scripts/staging-preflight.mjs` audits *resolved* Docker Compose JSON
  and requires loopback-only published ports (including database),
  read-only app filesystems, no privilege escalation, fake
  `sandbox` checkout flags on both services, no dev-bootstrap or
  bootstrap credentials, no public API docs, a deliberately isolated
  staging rehearsal database name, matching internal DB credentials,
  and web requests routed only to the private API.
- `scripts/release-readiness.test.mjs` uses Node's built-in test runner
  to prove the guards reject unsafe, forked, cyclical, dangling or
  publicly bound configurations with no external services.

## GitHub Actions

`.github/workflows/release-readiness.yml` runs on pull requests and
supports an optional manual `workflow_dispatch` when GitHub makes that
workflow available on the default branch. It executes read-only PR
inspection, tests the guards, then renders a local, **synthetic** Compose
configuration using temporary random credentials and runs the staging
preflight against it. Random values and the resolved config are kept in
temporary files and deleted; they are never uploaded as artifacts.

This workflow does **not** build/deploy a staging service or expose
any public application or DB port. Existing CI separately exercises
ephemeral Docker, Spring Boot, PostgreSQL and browser scenarios.

## Review/merge runbook (owner approval required)

1. Keep `main` unchanged until a release branch/merge plan is approved.
2. Review each PR in dependency order, from the earliest ancestor
   toward the latest descendant. Verify the actual source diff,
   migration numbering V1..V30, backwards compatibility and latest
   exact-head workflow results.
3. Decide a Git integration method before merging: **squashing a
   parent PR can leave the unmerged child branches holding original
   commits**. Retarget/rebase dependent PRs deliberately, verify their
   diffs and rerun all checks. Do not bulk merge, auto-delete parent
   branches or force push as a supposed shortcut.
4. Once a candidate exists on a protected integration branch, rehearse
   clean install AND migration upgrade of an existing disposable
   PostgreSQL database. Rehearse backup/restore with synthetic data.
5. Explicitly provision a separately authorized staging hosting
   environment and unique isolated secrets. Do not reuse the CI
   fake provider keys or passwords, publicize the local HTTPS edge,
   or point staging at production DB/provider.
6. Manually verify operator-role controls, financial audit invariants,
   consent, receipts, cash handoffs and incident response before any
   public pilot. Genuine QRIS/VA, messaging and bank settlement are
   **not implemented or authorized** by this release gate.

### Remaining release blockers

No protected live staging host, DNS, certificate, isolated hosted
database, secret manager, backup retention/recovery RPO/RTO, verified
real payment provider, approved receipt sender or legal/privacy review
is established by this work. Production deployment is explicitly
out of scope. The very long stacked PR graph itself requires a
controlled integration and review strategy before merging.
