# ---- Build Stage ----
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY ../../../pom.xml .
COPY ../.. ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests -q

# ---- Run Stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -S codeanalyzer && adduser -S codeanalyzer -G codeanalyzer

COPY --from=builder /app/target/code-analyzer-1.0.0.jar app.jar

# Copy .env if it exists (optional at runtime)
COPY --chown=codeanalyzer:codeanalyzer .env* ./

USER codeanalyzer

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD wget -q --spider http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["java", \
  "-Xms256m", \
  "-Xmx2g", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-jar", "app.jar"]
