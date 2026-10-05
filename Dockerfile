FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS frontend
WORKDIR /build/horizen-agent-web
COPY horizen-agent-web/package.json horizen-agent-web/package-lock.json ./
RUN npm ci
COPY horizen-agent-web/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-jammy AS backend
WORKDIR /build
COPY . .
COPY --from=frontend /build/horizen-agent-web/dist/ ./horizen-agent-web/dist/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -Pwith-frontend -pl horizen-agent-web -am package -DskipTests \
    && cp horizen-agent-web/target/horizen-agent-web-*.jar /build/horizen-agent.jar

FROM eclipse-temurin:17-jre-jammy
ARG VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.title="Horizen Agent" \
    org.opencontainers.image.description="Java agent runtime and local Web console" \
    org.opencontainers.image.source="https://github.com/xichaodong/horizen-agent" \
    org.opencontainers.image.licenses="Apache-2.0" \
    org.opencontainers.image.version=$VERSION
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gosu \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 horizen \
    && useradd --uid 10001 --gid 10001 --home-dir /var/lib/horizen-agent --no-create-home --shell /usr/sbin/nologin horizen \
    && mkdir -p /opt/horizen-agent /run/horizen-agent /var/lib/horizen-agent \
    && chown 10001:10001 /var/lib/horizen-agent
COPY --from=backend /build/horizen-agent.jar /opt/horizen-agent/app.jar
COPY scripts/container-entrypoint.sh /usr/local/bin/horizen-agent
RUN chmod 755 /usr/local/bin/horizen-agent
WORKDIR /var/lib/horizen-agent
EXPOSE 8787
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:8787/api/status || exit 1
ENTRYPOINT ["/usr/local/bin/horizen-agent"]
CMD ["demo"]
