#!/usr/bin/env bash
# No Aiven credential, merchant data, or network calls required.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
script=backend/docker-review-entrypoint.sh
sh -n "$script"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch" /tmp/scs-aiven-truststore.p12' EXIT
cat > "$scratch/java" <<'STUB'
#!/bin/sh
printf '%s\\n' 'TEST_STUB_JAVA_LAUNCHED'
STUB
chmod 700 "$scratch/java"

normal="$(env SCS_MYSQL_REVIEW_ENABLED=false PATH="$scratch:$PATH" sh "$script" 2>&1)"
[[ "$normal" == *'TEST_STUB_JAVA_LAUNCHED'* ]] || {
  echo "PostgreSQL/default startup did not run Java" >&2
  exit 1
}

set +e
missing="$(env -u SCS_AIVEN_CA_PEM_BASE64 SCS_MYSQL_REVIEW_ENABLED=true PATH="$scratch:$PATH" sh "$script" 2>&1)"
missing_status=$?
set -e
[[ "$missing_status" -ne 0 && "$missing" == *'Aiven public CA not configured'* ]] || {
  echo "Isolated review must refuse startup when CA is missing" >&2
  exit 1
}
[[ "$missing" != *'TEST_STUB_JAVA_LAUNCHED'* ]] || {
  echo "Java was launched without verified review CA" >&2
  exit 1
}

set +e
invalid="$(env SCS_AIVEN_CA_PEM_BASE64='not-valid-CA' SCS_MYSQL_REVIEW_ENABLED=true PATH="$scratch:$PATH" sh "$script" 2>&1)"
invalid_status=$?
set -e
[[ "$invalid_status" -ne 0 && "$invalid" == *'invalid CA'* ]] || {
  echo "Isolated review must refuse an invalid public CA" >&2
  exit 1
}
[[ "$invalid" != *'TEST_STUB_JAVA_LAUNCHED'* ]] || {
  echo "Java was launched with an invalid CA" >&2
  exit 1
}

# A disposable PUBLIC certificate proves the truststore is actually generated.
# The generated key never goes into app config or Vercel.
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
  -subj '/CN=scs-review-synthetic-ci' \
  -keyout "$scratch/key.pem" -out "$scratch/ca.pem" >/dev/null 2>&1
encoded="$(base64 -w0 "$scratch/ca.pem")"
valid="$(env SCS_AIVEN_CA_PEM_BASE64="$encoded" SCS_MYSQL_REVIEW_ENABLED=true PATH="$scratch:$PATH" sh "$script" 2>&1)"
[[ "$valid" == *'TEST_STUB_JAVA_LAUNCHED'* ]] || {
  echo "Valid public CA failed to start Java" >&2
  exit 1
}
[[ -s /tmp/scs-aiven-truststore.p12 ]] || {
  echo "Review TLS truststore was not created" >&2
  exit 1
}
keytool -list -storetype PKCS12 -storepass 'scs-public-ca-only' \
  -keystore /tmp/scs-aiven-truststore.p12 \
  -alias scs-aiven-review >/dev/null
echo "PASS: default startup, missing CA rejection, invalid CA rejection and synthetic TLS truststore"
