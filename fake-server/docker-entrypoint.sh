#!/bin/sh
set -e

chown minecraft:minecraft /data 2>/dev/null || true

exec su-exec minecraft java -jar /app/app.jar "$@"
