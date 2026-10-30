#!/usr/bin/env bash
# Verify effective Docker security settings on disposable Compose app containers.
# Does not touch persistent DBs, inspect credentials, or modify container state.
set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^(api|web|api_replica)$ ]]; then
  echo "Usage: bash scripts/verify-container-sandbox.sh api|web|api_replica" >&2
  exit 2
fi
service="$1"
if [[ "$service" == "api_replica" ]]; then
  id="$(docker compose -f compose.yaml -f infra/perf/compose.replicas.yaml ps -q "$service")"
else
  id="$(docker compose ps -q "$service")"
fi
if [[ -z "$id" ]]; then
  echo "FAIL: $service container is not running." >&2
  exit 1
fi

# Inspect Docker engine runtime state, not just Compose YAML declarations.
docker inspect "$id" | jq -e --arg svc "$service" '
  .[0] as $container |
  $container.HostConfig as $host |
  $container.Config as $cfg |
  $host.ReadonlyRootfs == true and
  $host.PidsLimit == 512 and
  ($host.CapDrop | index("ALL") != null) and
  ($host.SecurityOpt | any(startswith("no-new-privileges"))) and
  ($cfg.User == (if $svc == "web" then "node" else "cafe" end)) and
  ($host.Tmpfs | has("/tmp")) and
  ($host.Tmpfs["/tmp"] | contains("nosuid")) and
  ($host.Tmpfs["/tmp"] | contains("nodev")) and
  (if $svc == "web" then
    ($host.Tmpfs | has("/app/.next/cache")) and
    ($host.Tmpfs["/app/.next/cache"] | contains("nosuid")) and
    ($host.Tmpfs["/app/.next/cache"] | contains("nodev"))
  else true end)
' > /dev/null || {
  echo "FAIL: $service runtime sandbox does not match expected controls." >&2
  exit 1
}

# Execute as the actual unprivileged app user to prove bounded temp writes
# are possible but writing new files to the image filesystem is rejected.
runtime_test='
  check="/tmp/scs-ci-sandbox-probe-$$"
  touch "$check"
  rm "$check"
  if touch /app/scs-ci-forbidden-image-write 2>/dev/null; then
    rm -f /app/scs-ci-forbidden-image-write
    echo "FAIL: application image filesystem is unexpectedly writable" >&2
    exit 1
  fi
  if [ "$1" = web ]; then
    check="/app/.next/cache/scs-ci-cache-probe-$$"
    touch "$check"
    rm "$check"
  fi
'
if [[ "$service" == "api_replica" ]]; then
  docker compose -f compose.yaml -f infra/perf/compose.replicas.yaml exec -T "$service" \
    sh -ec "$runtime_test" sh "$service"
else
  docker compose exec -T "$service" sh -ec "$runtime_test" sh "$service"
fi
echo "PASS: $service non-root read-only root, dropped capabilities, no privilege escalation, process cap and bounded volatile write paths."
