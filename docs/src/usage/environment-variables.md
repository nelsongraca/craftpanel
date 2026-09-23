# Environment Variables, Ports & Volumes

This page is the complete reference for configuring a CraftPanel deployment. It covers three
layers you will touch:

1. **Compose `.env`** — the values you fill in before `docker compose up`.
2. **Container environment variables** — what each container (master, agent, frontend) reads.
3. **Ports and volumes** — what must be reachable and what must persist.

For the runtime settings that live in the database and are edited from the **Settings** page
(metric retention, port ranges, rate limits, base images), see
[Configuration & Secrets → Runtime settings](../tech-stack/configuration.md#runtime-settings-db-backed-editable-in-the-ui).
Those are **not** environment variables.

## How configuration is resolved

Master and the agent read configuration from environment variables. Master additionally ships a
bundled HOCON file (`application.conf`, packaged inside the image — not user-editable) that
provides defaults; every environment variable overrides the matching default.

For the secret values that support the `_FILE` pattern, resolution order is:

```
<NAME>_FILE secret file   ← highest priority
  ↓
<NAME> environment variable
  ↓
built-in default           ← lowest priority
```

There is no external config-file path, `--config` flag, or `CRAFTPANEL_CONFIG` variable — configure
through environment variables. See [Secret files](#secret-files-_file-pattern) below.

## Compose `.env` variables

These are the values the bundled `docker-compose.yml` reads from the `.env` file next to it. See
[Deployment](deployment.md) for the copy-and-fill walkthrough.

| Variable                | Required | Default                      | Description                                                                                                   |
|-------------------------|----------|------------------------------|---------------------------------------------------------------------------------------------------------------|
| `DOMAIN`                | Yes      | —                            | Public hostname players and admins use. Used for the Traefik `Host()` rule and to build `PUBLIC_URLS`.        |
| `ACME_EMAIL`            | Yes      | —                            | Let's Encrypt registration email.                                                                             |
| `DB_PASSWORD`           | Yes      | —                            | PostgreSQL password. Passed to both the `db` and `master` services.                                           |
| `JWT_SECRET`            | Yes      | —                            | JWT signing key. Minimum 32 characters. Master refuses to start on the default or a short value.              |
| `NODE_BOOTSTRAP_TOKEN`  | Yes      | —                            | Shared secret agents use for first-time registration. Minimum 16 characters. Must match every agent.          |
| `FORWARDING_KEY`        | Yes      | —                            | Base64-encoded 32-byte AES-256 key encrypting the stored proxy-forwarding secret. Generate with `openssl rand -base64 32`. |
| `ADMIN_EMAIL`           | Yes\*    | —                            | Email for the initial Super Admin, seeded only when the users table is empty.                                 |
| `ADMIN_PASSWORD`        | Yes\*    | —                            | Password for the initial Super Admin. Stored as an Argon2id hash.                                             |
| `ADMIN_RESET_PASSWORD`  | No       | `false`                      | Set `true` (with a new `ADMIN_PASSWORD`) to force-reset the admin password on the next master restart.         |
| `HOST_DATA_PATH`        | No       | `/opt/craftpanel/data`       | Host directory where server data lives. Must exist before the agent starts and must be a bind-mount.          |
| `IMAGE_VERSION`         | No       | `latest`                     | Image tag to deploy for `master`, `frontend`, and `agent`. See [Upgrading](upgrading.md).                     |
| `DNS_PROVIDER`          | No       | `none`                       | DNS provider identifier, e.g. `cloudflare`. **Not wired into the bundled compose file** — add it to the `master` service manually. See [Enabling Public Hostnames](enabling-public-hostnames.md). |
| `CF_API_TOKEN`          | No       | —                            | Cloudflare API token, required when `DNS_PROVIDER=cloudflare`. **Not wired into the bundled compose file** — add it to the `master` service manually. |

\* The bundled compose file marks `ADMIN_EMAIL` and `ADMIN_PASSWORD` as required (`:?required`) at
compose-parse time. The seed itself only runs once, against an empty users table. Because compose
still requires the variables to be *set*, blanking or deleting them makes `docker compose up` fail
before master starts. See [Retiring the admin seed credentials](troubleshooting.md#retiring-the-admin-seed-credentials).

## Master container variables

Read by the `master` service. Required values are enforced outside `CRAFTPANEL_PROFILE=dev`; with
`dev`, master logs warnings instead of refusing to start.

| Variable                         | Required | Default                          | Description                                                                                                   |
|----------------------------------|----------|----------------------------------|---------------------------------------------------------------------------------------------------------------|
| `DATABASE_URL`                   | Yes      | `jdbc:postgresql://localhost:5432/craftpanel` | JDBC connection string.                                                                            |
| `DATABASE_USERNAME`              | Yes      | `craftpanel`                     | Database user.                                                                                                |
| `DATABASE_PASSWORD`              | Yes      | —                                | Database password. Supports `DATABASE_PASSWORD_FILE`.                                                          |
| `DATABASE_POOL_SIZE`             | No       | `10`                             | HikariCP maximum pool size.                                                                                   |
| `JWT_SECRET`                     | Yes      | —                                | JWT signing key, minimum 32 bytes. Supports `JWT_SECRET_FILE`.                                                |
| `NODE_BOOTSTRAP_TOKEN`           | Yes      | `changeme`                       | Token agents use for first registration, minimum 16 characters. Supports `NODE_BOOTSTRAP_TOKEN_FILE`.         |
| `FORWARDING_KEY`                 | Yes      | dev-only weak default            | Base64 of 32 raw bytes (AES-256). Supports `FORWARDING_KEY_FILE`. See [Forwarding key](../tech-stack/configuration.md#forwarding-key). |
| `PUBLIC_URLS`                    | Yes\*    | —                                | Comma-separated full URLs of every browser origin that calls the API, e.g. `https://panel.example.com`. \*Required outside `dev` — with no value and a non-`dev` profile, CORS allows no origins and every browser request is rejected with `403`. |
| `HTTP_PORT`                      | No       | `8080`                           | Ktor HTTP bind port (binds `0.0.0.0`; not configurable). Health probe: `GET /health`.                          |
| `GRPC_PORT`                      | No       | `50051`                          | gRPC bind port for the control and bulk-data services.                                                         |
| `GRPC_CERT_STORE_PATH`           | No       | `/app/certs`                     | Directory for the auto-generated CA and server certificates. Mount a persistent volume.                       |
| `GRPC_TLS_SANS`                  | No       | —                                | Comma-separated extra SANs to add to the auto-generated server cert (add the master hostname/IP agents dial).  |
| `GRPC_TLS_CERT`                  | No       | —                                | BYOC: path to a server certificate. Overrides auto-generation. See [Custom gRPC TLS](custom-tls.md).            |
| `GRPC_TLS_KEY`                   | No       | —                                | BYOC: path to the matching private key (required with `GRPC_TLS_CERT`).                                        |
| `DNS_PROVIDER`                   | No       | `none`                           | DNS provider identifier. Only `cloudflare` is implemented.                                                     |
| `CF_API_TOKEN`                   | No       | —                                | Cloudflare API token; required when `DNS_PROVIDER=cloudflare`. Supports `CF_API_TOKEN_FILE`.                   |
| `AUTH_SECURE_COOKIES`            | No       | `true`                           | Set the `Secure` flag on auth cookies. Set `false` only in dev behind plain HTTP.                              |
| `AUTH_COOKIE_DOMAIN`             | No       | —                                | Shared parent domain for the refresh-token cookie (e.g. `.example.com`); only for a split-subdomain deploy.    |
| `CRAFTPANEL_PROFILE`             | No       | `prod`                           | `dev` relaxes CORS, skips the HSTS header, and skips secret-strength validation. Never use in production.     |
| `CRAFTPANEL_CONTAINER_PREFIX`    | No       | `craftpanel`                     | Prefix master applies to the Docker resource names it tracks (host-port/network bookkeeping). Keep in sync with the agent's prefix when running multiple isolated stacks on one daemon. |
| `DOCKER_ENDPOINT`                | No       | —                                | Docker host used for Swarm overlay network management (e.g. `unix:///var/run/docker.sock`). When set, master can create/delete overlay networks for Server Networks and allow cross-node membership. |
| `CRAFTPANEL_ADMIN_EMAIL`         | No       | —                                | Initial Super Admin email. Seed runs once, only against an empty users table.                                  |
| `CRAFTPANEL_ADMIN_PASSWORD`      | No       | —                                | Initial Super Admin password (Argon2id).                                                                       |
| `CRAFTPANEL_ADMIN_USERNAME`      | No       | `admin`                          | Username for the initial admin. Login is email-only; this is a display/handle value.                           |
| `CRAFTPANEL_ADMIN_RESET_PASSWORD`| No       | `false`                          | Force-reset the admin password on startup (see [Reset the admin password](../tech-stack/configuration.md#reset-the-admin-password)). |

## Agent container variables

Read by the `agent` service on each node. See [Node Management](../nodes/index.md) for the
registration protocol and data-path rules.

| Variable                            | Required       | Default                          | Description                                                                                                   |
|-------------------------------------|----------------|----------------------------------|---------------------------------------------------------------------------------------------------------------|
| `MASTER_HOST`                       | No             | `localhost`                      | Master hostname for the gRPC control channel.                                                                  |
| `MASTER_GRPC_PORT`                  | No             | `50051`                          | Master gRPC port.                                                                                              |
| `MASTER_HTTP_PORT`                  | No             | `8080`                           | Master HTTP port, used for private-IP auto-discovery.                                                          |
| `NODE_BOOTSTRAP_TOKEN`              | Yes (non-dev)  | `changeme`                       | Token used for first-time registration; must match master's value, minimum 16 characters. Supports `NODE_BOOTSTRAP_TOKEN_FILE`. Not needed once the node key is persisted. |
| `APP_PROFILE`                       | No             | `prod`                           | `dev` allows a default/short bootstrap token and plaintext gRPC (warns instead of failing).                   |
| `GRPC_TLS_CERT`                     | No             | —                                | Explicit path to master's CA cert PEM; takes priority over the auto-fetched cert.                              |
| `GRPC_CA_CERT_FILE`                 | No             | `/app/config/grpc-ca.crt`        | Where the agent persists/reads the CA cert received from master. Mount a writable volume.                      |
| `NODE_KEY_FILE`                     | No             | `/app/config/node.key`           | Where the agent persists its node key after registration. Mount a writable volume.                             |
| `DOCKER_SOCKET`                     | No             | `unix:///var/run/docker.sock`    | Docker daemon socket used to manage containers on this node.                                                   |
| `DATA_PATH`                         | No             | `/data`                          | Path **inside the agent container** where server data lives (file browser, backups, migrations).               |
| `HOST_DATA_PATH`                    | No             | value of `DATA_PATH`             | Path **on the host** Docker uses as the bind-mount source. Must be absolute and exist.                          |
| `SERVERS_BY_NAME_PATH`              | No             | `$DATA_PATH/servers-by-name`     | Root of the human-readable `servers-by-name/<name>` symlink overlay.                                           |
| `BACKUPS_BY_SERVER_PATH`            | No             | `$DATA_PATH/backups-by-server`   | Root of the `backups-by-server/<name>/<timestamp>.tar.gz` symlink overlay.                                     |
| `CRAFTPANEL_NETWORK`                | No             | `craftpanel`                     | Docker network shared by the agent, mc-router, and server containers. Must exist before the agent starts.      |
| `CRAFTPANEL_CONTAINER_PREFIX`       | No             | `craftpanel`                     | Prefix applied to all container/network names this agent creates. Change only for multiple isolated stacks on one daemon. |
| `NODE_HOSTNAME`                     | No             | auto-detected                    | Overrides the hostname reported to master (useful when the container hostname is an ephemeral ID).             |
| `NODE_PRIVATE_IP`                   | No             | auto-discovered                  | Overrides the private IP reported to master.                                                                   |
| `NODE_PUBLIC_IP`                    | No             | —                                | Overrides the public IP reported to master; takes priority over `PUBLIC_IP_URL`.                                |
| `PUBLIC_IP_URL`                     | No             | —                                | External service used to detect this node's public IP (e.g. `https://api.ipify.org`).                          |
| `SYSTEM_RESERVED_RAM_MB`            | No             | `0`                              | RAM (MB) reserved for the host OS, excluded from allocatable capacity.                                         |
| `SYSTEM_RESERVED_CPU_MILLICORES`    | No             | `0`                              | CPU millicores (1000 per core) reserved for the host OS, excluded from allocatable capacity.                   |
| `MCROUTER_IMAGE`                    | No             | `itzg/mc-router:latest`          | Image used for the shared mc-router container.                                                                 |
| `MCROUTER_UPDATE_ON_START`          | No             | `true`                           | Whether to pull a fresh mc-router image on agent start. Set `false` to use the cached image.                    |
| `MCROUTER_CONTAINER_NAME`           | No             | `craftpanel-mc-router`           | Overrides the mc-router container name.                                                                        |
| `MCROUTER_ENABLED`                  | No             | `true`                           | When `false`, the agent never provisions, attaches, detaches, or metrics-queries mc-router.                    |
| `METRICS_POLL_INTERVAL_SECONDS`     | No             | `5`                              | Polling interval for node and container metrics. Minimum 1.                                                    |
| `METRICS_COLLECTION_CONCURRENCY`    | No             | `8`                              | Max server containers collected in parallel per metrics tick.                                                  |
| `AGENT_RECONCILE_INTERVAL_SECONDS`  | No             | `30`                             | Cadence of the convergence backstop sweep. `0` disables the sweep.                                             |
| `PULL_MAX_IMAGE_AGE_HOURS`          | No             | `24`                             | Max age of a locally-cached image before a fresh pull is attempted.                                            |
| `HEARTBEAT_FILE`                    | No             | `/tmp/agent-heartbeat`           | Internal — path the agent touches on successful auth and every metrics tick; used by the container healthcheck. |

!!! note
    The master and agent both read `CRAFTPANEL_CONTAINER_PREFIX`, but they serve different purposes:
    master uses it for the Docker resources it tracks, the agent for the containers and networks it
    creates. Set them to the same value.

## Frontend container variables

Read by the `frontend` service.

| Variable                             | Default                 | Description                                                                                                   |
|--------------------------------------|-------------------------|---------------------------------------------------------------------------------------------------------------|
| `MASTER_URL`                         | `http://localhost:8080` | Server-side base URL for the `/healthz` version check and the `/api/*` catch-all proxy route. Both compose files set it to `http://master:8080` — never `localhost` inside a container. |
| `PUBLIC_API_URL`                     | — (same-origin)         | Browser-facing master origin, e.g. `https://api.example.com`. Only for a split-subdomain deploy. Leave unset for the default single-domain deploy. |
| `NEXT_PUBLIC_CRAFTPANEL_BUILD_VERSION` | `unknown`             | Build-time only. The version string baked in by the Gradle build; reported by `/healthz`. Not set at runtime.  |
| `DEV_ALLOWED_ORIGINS`                | —                       | Dev-only (`next dev`): comma-separated extra origins allowed to load HMR/client chunks (e.g. a LAN IP).        |
| `DEV_API_PROXY`                      | —                       | Dev-only (`next dev`): proxies `/api/*` to a running master (e.g. `http://localhost:8080`).                    |

## Secret files (`_FILE` pattern)

For the secrets below, setting `<NAME>_FILE` to a path makes master (or the agent, for the bootstrap
token) read the secret from that file — contents trimmed — instead of the plain variable. This is
compatible with Docker secrets and Kubernetes secret mounts. A `_FILE` path that is set but
unreadable is **fatal** (fail loud rather than silently fall back).

```bash
# Instead of:
DATABASE_PASSWORD=s3cr3t

# Use:
DATABASE_PASSWORD_FILE=/run/secrets/db_password
```

| `_FILE` variable               | Read by      | Description                                             |
|--------------------------------|--------------|---------------------------------------------------------|
| `DATABASE_PASSWORD_FILE`       | master       | PostgreSQL password                                     |
| `JWT_SECRET_FILE`              | master       | JWT signing key                                         |
| `CF_API_TOKEN_FILE`            | master       | Cloudflare API token                                    |
| `NODE_BOOTSTRAP_TOKEN_FILE`    | master, agent| Node registration bootstrap token                       |
| `FORWARDING_KEY_FILE`          | master       | AES-256 key encrypting the stored forwarding secret     |

## Ports

| Port            | Service                | Default     | Needs to be reachable from        | Purpose                                                              |
|-----------------|------------------------|-------------|-----------------------------------|----------------------------------------------------------------------|
| `80`            | Traefik                | `80`        | the internet                      | HTTP; redirects to HTTPS                                             |
| `443`           | Traefik                | `443`       | the internet                      | HTTPS UI and API (WSS included)                                      |
| `50051`         | master gRPC            | `50051`     | every agent host                  | Control stream + bulk-data transfers                                 |
| `25565`         | mc-router              | `25565`     | players                           | Minecraft player ingress (per node)                                  |
| `8080`          | master HTTP            | `8080`      | Traefik/frontend (internal)       | REST API; health probe at `GET /health`                              |
| `3000`          | frontend               | `3000`      | Traefik (internal)                | Next.js server; health probe at `GET /healthz`                       |
| `5432`          | PostgreSQL             | `5432`      | master (internal)                 | Database                                                             |
| `25570`–`26070` | per-server host ports  | from Settings | the internet (only if exposing standalone servers) | Host-port pool for servers not behind mc-router       |
| `25565`         | game server (internal) | `25565`     | mc-router                         | In-container game port                                               |
| `25577`         | proxy (internal)       | `25577`     | mc-router                         | In-container Velocity/BungeeCord port                                |

Only ports `80`, `443`, and `25565` must be open on the public internet for the default
single-node, Traefik-fronted deploy. Port `50051` is only needed on the master host if you attach
agents from other machines. The port range (`default_port_range_start` / `default_port_range_end`)
is a runtime setting edited from the **Settings** page, not an environment variable.

## Volumes & data paths

The bundled compose file defines four named volumes plus one bind-mount:

| Volume / mount                | Service        | Purpose                                                                                   |
|-------------------------------|----------------|-------------------------------------------------------------------------------------------|
| `db-data`                     | `db`           | PostgreSQL data directory. Back this up before upgrading.                                  |
| `master-certs`                | `master`       | Auto-generated gRPC CA and server certificate. Losing it forces agents to re-trust.        |
| `agent-config`                | `agent`        | Persisted node key and cached CA cert. Losing it forces re-registration.                   |
| `traefik-certs`               | `traefik`      | ACME (Let's Encrypt) certificates.                                                         |
| `${HOST_DATA_PATH}:/data`     | `agent`        | **Bind-mount**, not a named volume — server data. Must exist on the host before start.     |

Server data lives at `{HOST_DATA_PATH}/servers/{name}` on the host, where `{name}` defaults to the
server id and can be overridden per server by an admin. See
[Data path alignment](../nodes/index.md#data-path-alignment) for the three values that must stay in
sync.

## Related

- [Deployment](deployment.md) — first-time setup
- [Upgrading](upgrading.md) — changing `IMAGE_VERSION` and migrating the database
- [Custom gRPC TLS](custom-tls.md) — bring-your-own certificates
- [Troubleshooting](troubleshooting.md) — startup validation failures
- [Configuration & Secrets](../tech-stack/configuration.md) — architecture-level reference and DB-backed runtime settings
