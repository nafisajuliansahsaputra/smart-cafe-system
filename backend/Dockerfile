FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 cafe
COPY --from=build /source/target/smart-cafe-backend-0.1.0-SNAPSHOT.jar app.jar
USER cafe
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
