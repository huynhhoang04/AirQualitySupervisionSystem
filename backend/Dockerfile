# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

# Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/aqss-backend-*.jar app.jar

# Size the heap from the container limit (Render free = 512 MB) instead of the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The app binds $PORT when the platform injects one (Render/Railway/Cloud Run), else 8080.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
