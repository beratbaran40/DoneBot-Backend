# syntax=docker/dockerfile:1.7

# --- Stage 1: build ---
FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace

# Copy Gradle wrapper + build files first for layer caching
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# Keep the Gradle home in a BuildKit cache mount, not in the image layer. This speeds up
# rebuilds AND keeps ~/.gradle out of the build-stage snapshot that `COPY --from=build`
# checksum-walks — that walk was failing intermittently on a transient cache file
# (org.apiguardian) with "no such file or directory".
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Now copy sources and build
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

# --- Stage 2: runtime ---
FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
