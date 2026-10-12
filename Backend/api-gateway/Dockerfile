FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd -r javauser && useradd -r -g javauser javauser
COPY --from=builder /app/target/*.jar app.jar
RUN chown javauser:javauser app.jar
USER javauser

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8085
HEALTHCHECK --interval=20s --timeout=10s --retries=5 --start-period=30s \
    CMD curl -f http://localhost:8085/api/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
