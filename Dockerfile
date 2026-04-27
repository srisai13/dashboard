# Build stage
FROM maven:3.8-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY dashboard_files ./dashboard_files
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/dashboard-api-1.0.0-SNAPSHOT.jar app.jar
COPY --from=build /app/dashboard_files ./dashboard_files
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
