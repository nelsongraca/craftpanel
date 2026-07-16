# Deployment

CraftPanel ships as three Docker images (`master`, `frontend`, `agent`) plus PostgreSQL. The repo root includes a ready-to-use `docker-compose.yml` that runs the full stack behind Traefik with automatic Let's Encrypt TLS, including one co-located node agent.

## Prerequisites

- A host with Docker and the Compose plugin installed
- A domain name pointed at the host's public IP
- Ports `80`, `443` open (HTTP/HTTPS via Traefik); `50051` open if you plan to attach agents on other hosts
- An empty directory on the host for server data (e.g. `/opt/craftpanel/data`)

## Configure

Copy `docker-compose.yml` from the repo root to your host, then create a `.env` file alongside it with the required values:

```bash
DOMAIN=panel.example.com
ACME_EMAIL=you@example.com
DB_PASSWORD=<random secret>
JWT_SECRET=<random secret, 32+ bytes>
NODE_BOOTSTRAP_TOKEN=<random secret, 16+ chars>
ADMIN_EMAIL=you@example.com
ADMIN_PASSWORD=<initial admin password>
HOST_DATA_PATH=/opt/craftpanel/data
```

See [Configuration & Secrets](../tech-stack/configuration.md) for the full environment variable reference, including the `_FILE` secret pattern for production deployments that prefer mounted secrets over plain env vars.

## Start the stack

```bash
mkdir -p "$HOST_DATA_PATH"
docker compose up -d
docker compose ps
```

Traefik requests a TLS certificate for `DOMAIN` on first boot — this can take up to a minute. Once `master`, `frontend`, and `db` report healthy, the UI is reachable at `https://<DOMAIN>`.

The compose file also starts an `agent` service on the same host, so the stack is immediately capable of running Minecraft servers without registering a second node. See [Adding a Node](adding-a-node.md) to attach agents running on other hosts.

## Next steps

- [First Login & Setup](first-login.md)
- [Adding a Node](adding-a-node.md)

## Building from source

To build images locally instead of pulling from `ghcr.io/nelsongraca`, see [Build & Packaging](../tech-stack/build-and-packaging.md).
