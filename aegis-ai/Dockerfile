# Multi-stage build: compile with Maven, ship only the JRE + jar.
# Keeps the runtime image small and free of build tooling / source.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependencies first so they cache across source-only changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Don't run the app as root inside the container.
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

COPY --from=build /app/target/aegis-ai-*.jar app.jar

EXPOSE 8080
# Container-aware heap sizing; -XX:+UseContainerSupport is default since JDK 11.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
