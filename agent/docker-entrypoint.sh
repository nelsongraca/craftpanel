#!/bin/sh
set -e
mkdir -p /app/config /data/servers /data/backups
chown craftpanel:craftpanel /app/config /data/servers /data/backups
exec su-exec craftpanel "$@"
