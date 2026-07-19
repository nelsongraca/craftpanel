#!/bin/sh
set -e
mkdir -p /app/config /data
chown craftpanel:craftpanel /app/config /data

if [ -S /var/run/docker.sock ]; then
    sock_gid=$(stat -c %g /var/run/docker.sock)
    if ! getent group "$sock_gid" >/dev/null 2>&1; then
        addgroup -g "$sock_gid" docker-sock
        addgroup craftpanel docker-sock
    elif ! id -Gn craftpanel | tr ' ' '\n' | grep -qx "$(getent group "$sock_gid" | cut -d: -f1)"; then
        addgroup craftpanel "$(getent group "$sock_gid" | cut -d: -f1)"
    fi
fi

exec su-exec craftpanel "$@"
