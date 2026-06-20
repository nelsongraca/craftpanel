#!/usr/bin/env bash
set -euo pipefail

PREFIX="craftpanel-"

containers=$(docker ps -aq --filter "name=$PREFIX")
if [ -n "$containers" ]; then
    echo "Stopping containers..."
    docker stop $containers
    echo "Removing containers..."
    docker rm $containers
else
    echo "No containers with prefix '$PREFIX' found."
fi

networks=$(docker network ls --filter "name=$PREFIX" -q)
if [ -n "$networks" ]; then
    echo "Removing networks..."
    for net in $networks; do
        docker network rm "$net" && echo "  removed $net" || echo "  skipped $net (in use)"
    done
else
    echo "No networks with prefix '$PREFIX' found."
fi