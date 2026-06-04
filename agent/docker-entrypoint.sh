#!/bin/sh
set -e
chown craftpanel:craftpanel /app/config
exec su-exec craftpanel "$@"
