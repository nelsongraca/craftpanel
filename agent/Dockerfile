FROM eclipse-temurin:25-jdk-alpine

ARG DOCKER_GID=999

RUN apk add --no-cache su-exec pv && \
    addgroup -g ${DOCKER_GID} docker || true && \
    addgroup -g 1000 craftpanel && \
    adduser -u 1000 -G craftpanel -S craftpanel && \
    adduser craftpanel docker

WORKDIR /app

COPY build/install/agent/ .
COPY docker-entrypoint.sh ./docker-entrypoint.sh

RUN mkdir -p config && chmod +x docker-entrypoint.sh

# Agent has no listening port (gRPC dials out to master); a heartbeat file is touched on
# successful auth and on every metrics tick, so this catches a JVM stuck in the reconnect
# loop (ConnectionManager) that a bare process-liveness check would miss.
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD find /tmp/agent-heartbeat -mmin -2 2>/dev/null | grep -q . || exit 1

ARG APP_VERSION=unknown
ENV APP_VERSION=$APP_VERSION

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["bin/agent"]
