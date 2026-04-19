# syntax=docker/dockerfile:1.7

# --- Stage 1: build ---
FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace

# Copy Gradle wrapper + build files first for layer caching
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Now copy sources and build
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# --- Stage 2: runtime ---
FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
