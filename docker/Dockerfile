FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
ENV MAVEN_OPTS="-Xmx512m -XX:ActiveProcessorCount=2"
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package
FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
# Patch available distribution fixes; the final OS-package scan records the resolved packages.
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
ARG MODULE
ARG SOURCE_REVISION=development
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-1.0.0.jar /app/application.jar
RUN mkdir -p /app/state && chown 10001:10001 /app/state && \
    case "$MODULE" in gateway-data-plane) printf 9080 > /app/health-port; printf /readiness > /app/health-path;; gateway-control-plane) printf 9081 > /app/health-port; printf /readiness > /app/health-path;; *) printf 8091 > /app/health-port; printf '' > /app/health-path;; esac
HEALTHCHECK --interval=5s --timeout=3s --start-period=180s --retries=60 CMD curl -fsS --max-time 2 "http://127.0.0.1:${HEALTH_PORT:-$(cat /app/health-port)}/actuator/health${HEALTH_PATH:-$(cat /app/health-path)}" >/dev/null || exit 1
USER 10001:10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/application.jar"]
