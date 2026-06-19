FROM itzg/mc-monitor:latest AS mc-monitor

FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache su-exec && \
    addgroup -g 1000 minecraft && \
    adduser -u 1000 -G minecraft -S minecraft

WORKDIR /app

COPY --from=mc-monitor /mc-monitor /usr/local/bin/mc-monitor

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
