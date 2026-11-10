# syntax=docker/dockerfile:1

# ---- stage 1: build with Maven -------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# compile, and gather the runtime libraries (H2, Jackson) next to the classes; tests run in CI, not here
RUN mvn -B -q -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime

# ---- stage 2: a small image that only runs the API ------------------------------
FROM eclipse-temurin:17-jre
# run as an ordinary user, never as root
RUN useradd --system --uid 10001 --home-dir /app bank \
 && mkdir -p /app/data \
 && chown -R bank /app
WORKDIR /app
COPY --from=build /build/target/classes ./classes
COPY --from=build /build/target/dependency ./lib
USER bank

EXPOSE 8080
# the database lives here; mount a volume to keep data between runs
VOLUME /app/data

# no curl needed: the app ships its own health check
HEALTHCHECK --interval=5s --timeout=5s --start-period=10s --retries=10 \
  CMD ["java", "-cp", "classes:lib/*", "basicbankingapp.HealthCheck", "http://localhost:8080/api/health"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "classes:lib/*", "basicbankingapp.BankingApiApp"]
# inside a container the API must listen on all interfaces; publish the port to localhost only (see docker-compose.yml)
CMD ["--host", "0.0.0.0", "--port", "8080"]
