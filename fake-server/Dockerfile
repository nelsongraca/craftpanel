FROM itzg/mc-monitor:latest AS mc-monitor

# mc-image-helper is only used by system tests to validate PatchSet JSON against the real
# parser (see ProxySettingsTest). Fetched from its own GitHub release, not the full
# itzg/mc-proxy image, and checksum-verified. Pin the version — do not use a moving tag.
FROM alpine:3.22 AS mc-image-helper
ARG MC_IMAGE_HELPER_VERSION=1.63.0
ARG MC_IMAGE_HELPER_SHA256=e5c6f74e51630206324b2dbc58239eb1a8adca66d515a92d633a72f0972691ef
RUN apk add --no-cache curl && \
    curl -fsSL -o /tmp/mc-image-helper.tgz \
        "https://github.com/itzg/mc-image-helper/releases/download/${MC_IMAGE_HELPER_VERSION}/mc-image-helper-${MC_IMAGE_HELPER_VERSION}.tgz" && \
    echo "${MC_IMAGE_HELPER_SHA256}  /tmp/mc-image-helper.tgz" | sha256sum -c - && \
    mkdir -p /opt/mc-image-helper && \
    tar -xzf /tmp/mc-image-helper.tgz -C /opt/mc-image-helper --strip-components=1

FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache su-exec && \
    addgroup -g 1000 minecraft && \
    adduser -u 1000 -G minecraft -S minecraft

WORKDIR /app

COPY --from=mc-monitor /mc-monitor /usr/local/bin/mc-monitor
COPY --from=mc-image-helper /opt/mc-image-helper /opt/mc-image-helper
RUN ln -s /opt/mc-image-helper/bin/mc-image-helper /usr/local/bin/mc-image-helper

COPY build/install/fake-server/ .
COPY docker-entrypoint.sh ./docker-entrypoint.sh

RUN chmod +x docker-entrypoint.sh && mkdir -p /data

EXPOSE 25565/tcp
EXPOSE 25565/udp

ARG SERVER_NAME="CraftPanel Fake Server"
ARG MOTD="A fake Minecraft server"
ARG MAX_PLAYERS=20
ARG STOP_COMMAND="stop"

ENV GAME_PORT=25565 \
    SERVER_NAME=$SERVER_NAME \
    MOTD=$MOTD \
    MAX_PLAYERS=$MAX_PLAYERS \
    ONLINE_PLAYERS="" \
    STOP_COMMAND=$STOP_COMMAND

HEALTHCHECK --interval=5s --timeout=5s --start-period=15s --retries=3 \
    CMD mc-monitor status --host localhost --port ${GAME_PORT}

ENTRYPOINT ["/app/docker-entrypoint.sh"]
