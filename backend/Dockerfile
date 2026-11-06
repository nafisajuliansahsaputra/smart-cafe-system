FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY pom.xml .
COPY src ./src
COPY review-diagnostic ./review-diagnostic
# Bound retries to transient Maven Central 429/502/503/504 only.
# Compilation/test failures are never retried or treated as successful builds.
RUN set -eu; \
    attempt=1; \
    while [ "$attempt" -le 3 ]; do \
      if mvn -B -ntp -U -DskipTests package > /tmp/maven-build.log 2>&1; then \
        cat /tmp/maven-build.log; \
        rm /tmp/maven-build.log; \
        exit 0; \
      fi; \
      cat /tmp/maven-build.log; \
      if ! grep -Eq 'status code: (429|502|503|504)|Connection reset|Read timed out|Connect timed out' /tmp/maven-build.log || [ "$attempt" -ge 3 ]; then \
        exit 1; \
      fi; \
      sleep $((attempt * 8)); \
      attempt=$((attempt + 1)); \
    done; \
    exit 1

# The diagnostic has no Spring context. The driver is copied from the same
# audited Maven dependency set used by the actual application. It does not
# perform repairs, data mutations or schema migrations.
RUN set -eu; \
    driver="$(find /root/.m2/repository/com/mysql/mysql-connector-j \
      -type f -name '*.jar' | head -n 1)"; \
    test -n "$driver"; \
    cp "$driver" /tmp/scs-review-mysql-driver.jar; \
    mkdir -p /tmp/scs-review-diagnostic; \
    javac -d /tmp/scs-review-diagnostic review-diagnostic/ReviewMySqlPreflight.java

FROM eclipse-temurin:21-jre
# The rolling Ubuntu 26.04 base contained a fixable HIGH apt/libapt-pkg
# advisory (CVE-2026-109518). Upgrade those runtime packages rather than
# suppressing the Trivy release gate. Do not retain the apt index layers.
RUN apt-get update \
    && apt-get install -y --only-upgrade --no-install-recommends apt libapt-pkg7.0 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN useradd --system --uid 10001 cafe
COPY --from=build /source/target/smart-cafe-backend-0.1.0-SNAPSHOT.jar app.jar
COPY --chown=cafe:cafe docker-review-entrypoint.sh /app/docker-review-entrypoint.sh
COPY --from=build --chown=cafe:cafe /tmp/scs-review-mysql-driver.jar /app/scs-review-mysql-driver.jar
COPY --from=build --chown=cafe:cafe /tmp/scs-review-diagnostic /app/scs-review-diagnostic
USER cafe
EXPOSE 8080
# Review startup builds its CA truststore in /tmp only when opt-in is true.
# Normal PostgreSQL startup remains the same, and no secrets enter the image.
ENTRYPOINT ["sh","/app/docker-review-entrypoint.sh"]
