#!/bin/sh
set -e

: "${UID:=1000}"
: "${GID:=1000}"
: "${SERVER_NAME:=CraftPanel Fake Server}"
: "${GAME_PORT:=25565}"
: "${MAX_PLAYERS:=20}"

cat > /data/server.properties <<EOF
level-name=world
motd=${SERVER_NAME}
server-port=${GAME_PORT}
max-players=${MAX_PLAYERS}
online-mode=false
EOF

if [ "$(id -u)" = 0 ]; then
  if [ "$UID" != 0 ] && [ "$UID" != "$(id -u minecraft)" ]; then
    usermod -u "$UID" minecraft
  fi
  if [ "$GID" != 0 ] && [ "$GID" != "$(id -g minecraft)" ]; then
    groupmod -g "$GID" minecraft
  fi
  if [ "$(stat -c '%u' /data 2>/dev/null)" != "$UID" ] 2>/dev/null; then
    chown -R minecraft:minecraft /data
  fi
  exec su-exec minecraft:minecraft java -jar /app/app.jar "$@"
else
  exec java -jar /app/app.jar "$@"
fi
