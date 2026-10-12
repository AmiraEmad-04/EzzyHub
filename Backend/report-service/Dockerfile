FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the built JAR (built with: mvn clean package -DskipTests)
COPY target/report-service-1.0.0.jar app.jar

# NEW: the old Dockerfile hardcoded a -Dspring.datasource.url override
# pointing at an H2 file volume — that would have silently overridden the
# MySQL config in application.properties even after fixing it there. Removed
# it; this now just uses application.properties (or standard
# SPRING_DATASOURCE_* environment variables in Docker/docker-compose) like
# the other services.

EXPOSE 8086

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
