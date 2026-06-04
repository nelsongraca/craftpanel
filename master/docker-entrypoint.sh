#!/bin/sh
set -e
chown craftpanel:craftpanel /app/certs
exec su-exec craftpanel "$@"
