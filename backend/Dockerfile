FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY pom.xml .
COPY src ./src
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

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 cafe
COPY --from=build /source/target/smart-cafe-backend-0.1.0-SNAPSHOT.jar app.jar
USER cafe
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
