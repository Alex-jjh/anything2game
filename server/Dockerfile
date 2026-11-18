# Anything2Game server image. Build from the repository root:
#   docker build -f server/Dockerfile -t a2g-server:dev .
# Run locally (publish on loopback only):
#   docker run --rm -p 127.0.0.1:18090:8080 a2g-server:dev
#
# The image contains no cloud SDK and no secrets. Everything it needs arrives as A2G_* environment variables (database URL and
# password, model keys, OIDC issuer) injected by the platform's secret store, so the same image runs on ECS/Fargate, Cloud Run
# or Alibaba Cloud unchanged. It keeps no state: use A2G_STORE=postgres in the cloud (the file store below is for local smoke tests).

# ---- build ---------------------------------------------------------------------------------------------------------------------
# gradle:9.4.1 is the version of gradle/wrapper/gradle-wrapper.properties. A2G_SERVER_ONLY=1 makes settings.gradle.kts skip the
# Android modules, so no Android SDK is needed.
FROM gradle:9.4.1-jdk21 AS build
ENV A2G_SERVER_ONLY=1
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY contract ./contract
COPY server ./server
RUN gradle --no-daemon --console=plain :server:installDist -x test

# ---- runtime -------------------------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --user-group --no-create-home --shell /usr/sbin/nologin a2g \
    && mkdir -p /app/data && chown a2g:a2g /app/data
WORKDIR /app
COPY --from=build /src/server/build/install/server/ /app/

# Inside a container the process must listen on all interfaces so the platform's load balancer / port mapping can reach it.
# That is not exposure by itself: who can reach the port is decided outside the image (ALB + security groups, Cloud Run ingress,
# `docker run -p 127.0.0.1:...`), never publish it on all host interfaces and terminate TLS in front of it.
ENV A2G_HOST=0.0.0.0 \
    A2G_DATA_DIR=/app/data \
    A2G_LOG_FORMAT=json \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# PORT (Cloud Run style) or A2G_PORT override the default.
EXPOSE 8080
USER a2g

# Liveness for plain Docker; orchestrators should probe GET /v1/health (alive) and GET /v1/ready (database reachable).
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT:-${A2G_PORT:-8080}}/v1/health" || exit 1

ENTRYPOINT ["/app/bin/server"]
