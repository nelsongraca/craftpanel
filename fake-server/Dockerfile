# Pull mc-monitor binary from the official image so our HEALTHCHECK
# behaves identically to a real itzg/minecraft-server container.
FROM itzg/mc-monitor:latest AS mc-monitor

FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache su-exec && \
    addgroup -g 1000 minecraft && \
    adduser -u 1000 -G minecraft -S minecraft

WORKDIR /app

# mc-monitor is a static Go binary — drop it straight in
COPY --from=mc-monitor /mc-monitor /usr/local/bin/mc-monitor

COPY build/libs/craftpanel-fake-server-test.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN chmod +x /app/docker-entrypoint.sh && mkdir -p /data

EXPOSE 25565/tcp
EXPOSE 25565/udp

ENV GAME_PORT=25565 \
    SERVER_NAME="CraftPanel Fake Server" \
    MOTD="A fake Minecraft server" \
    MAX_PLAYERS=20 \
    ONLINE_PLAYERS="" \
    STOP_COMMAND="stop"

# Mirrors the HEALTHCHECK in itzg/minecraft-server exactly.
# mc-monitor does a TCP status ping; the fake server responds via TcpPingServer.
# start_period gives the JVM time to start before health checks begin.
HEALTHCHECK --interval=5s --timeout=5s --start-period=15s --retries=3 \
    CMD mc-monitor status --host localhost --port ${GAME_PORT}

ENTRYPOINT ["/app/docker-entrypoint.sh"]
