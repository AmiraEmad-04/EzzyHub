# EzzyHub Java Service — Dockerfile (reuse for all 3 Java services)
# Multi-stage: build with Maven, run with slim JRE
# Usage: place this Dockerfile inside the service root alongside pom.xml

FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Cache Maven dependencies — re-downloaded only when pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the fat JAR
COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Security: run as non-root
RUN groupadd -r javauser && useradd -r -g javauser javauser

COPY --from=builder /app/target/*.jar app.jar

RUN chown javauser:javauser app.jar
USER javauser

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8081

HEALTHCHECK --interval=20s --timeout=10s --retries=5 --start-period=60s \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
