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

## Split-subdomain deploy (optional)

The default compose file serves `frontend` and `master` on the same domain, with Traefik
routing `/api` to `master`. If you'd rather run them on separate subdomains of the same
parent domain (e.g. `panel.example.com` for the frontend, `api.example.com` for the
master), set these on top of the base config:

```bash
# master service
AUTH_COOKIE_DOMAIN=.example.com   # shared parent domain — sends the refresh-token cookie cross-subdomain
PUBLIC_URLS=https://panel.example.com,https://api.example.com

# frontend service
PUBLIC_API_URL=https://api.example.com
```

Only a shared parent domain is supported — the refresh-token cookie can't be shared
across unrelated domains. Update the Traefik router rules for both services to match
their respective subdomains instead of the shared `Host(${DOMAIN})` rule.

## Next steps

- [First Login & Setup](first-login.md)
- [Adding a Node](adding-a-node.md)

## Building from source

To build images locally instead of pulling from `ghcr.io/nelsongraca`, see [Build & Packaging](../tech-stack/build-and-packaging.md).
