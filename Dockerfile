ARG JARFILE="trakt-1.0-SNAPSHOT.jar"

# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build scripts first (better layer caching)
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# Make gradlew executable and pre-download dependencies
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source and build the JAR
COPY src/ src/
RUN ./gradlew build --no-daemon

# Run the app!
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create a non-root user for security
RUN addgroup -S trakt && adduser -S trakt -G trakt

# Copy the fat JAR from the build stage
COPY --from=builder /app/build/libs/${JARFILE} trakt.jar

USER trakt

CMD ["java", "-jar", "trakt.jar"]
