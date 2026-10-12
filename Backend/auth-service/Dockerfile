FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the built JAR (built with: mvn clean package -DskipTests)
COPY target/auth-service-1.0.0.jar app.jar

EXPOSE 8087

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
