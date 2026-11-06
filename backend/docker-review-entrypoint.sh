#!/bin/sh
# Build an ephemeral JSSE truststore from the Aiven PUBLIC CA only for
# the deliberately opt-in synthetic MySQL review. No secrets in image.
set -eu
if [ "${SCS_MYSQL_REVIEW_ENABLED:-false}" = "true" ]; then
    if [ -z "${SCS_AIVEN_CA_PEM_BASE64:-}" ]; then
        echo 'MySQL review refused: Aiven public CA not configured.' >&2
        exit 1
    fi
    umask 077
    ca_file=/tmp/scs-aiven-public-ca.pem
    truststore=/tmp/scs-aiven-truststore.p12
    trap 'rm -f "$ca_file"' EXIT
    if ! printf '%s' "$SCS_AIVEN_CA_PEM_BASE64" | base64 -d > "$ca_file"; then
        echo 'MySQL review refused: invalid CA encoding.' >&2
        exit 1
    fi
    # Password protects only the truststore of one PUBLIC certificate,
    # not a database credential. Never print secret environment variables.
    if ! keytool -importcert -noprompt -trustcacerts \
       -alias scs-aiven-review -file "$ca_file" \
       -keystore "$truststore" -storetype PKCS12 \
       -storepass 'scs-public-ca-only' >/dev/null 2>&1; then
        echo 'MySQL review refused: invalid CA certificate.' >&2
        exit 1
    fi
    rm -f "$ca_file"
    trap - EXIT
    # Inspect only immutable Flyway metadata and object presence before
    # Spring starts. No SQL writes, Flyway repair, customer data or secrets.
    java -cp '/app/scs-review-diagnostic:/app/scs-review-mysql-driver.jar' \
      ReviewMySqlPreflight
fi
# Fast startup only for the isolated, strictly verified Aiven review.
# Other deployments retain their original JVM settings.
if [ "${SCS_MYSQL_REVIEW_ENABLED:-false}" = "true" ]; then
    exec java -XX:TieredStopAtLevel=1 -jar /app/app.jar
fi
# Short-lived Hobby container cold starts have a strict ~29-second port-bind
# deadline. C1-only JIT compilation brings the synthetic MySQL Preview API
# to readiness sooner without changing schemas, TLS, or payment safeguards.
# Leave the regular PostgreSQL/merchant runtime unchanged.
if [ "${SCS_MYSQL_REVIEW_ENABLED:-false}" = "true" ]; then
    exec java -XX:TieredStopAtLevel=1 -jar /app/app.jar
fi
exec java -jar /app/app.jar
