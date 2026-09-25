# Configuration & Secrets

CraftPanel master is configured through a layered system: a bundled HOCON config file provides defaults, environment variables override any default, and sensitive values can be supplied as mounted secret files.

## Precedence

For secrets (`JWT_SECRET`, `DATABASE_PASSWORD`, `NODE_BOOTSTRAP_TOKEN`, `FORWARDING_KEY`):

```
Secret file (_FILE env var)  ← highest priority
  ↓
Environment variable
  ↓
Built-in default              ← lowest priority
```

For all other values: environment variable, else built-in default.

## Config file

Master uses Ktor's bundled HOCON config, `application.conf` (packaged inside the master distribution — not a user-supplied file). It defines defaults and wires each value to an optional environment-variable override via HOCON `${?ENV_VAR}` syntax. There is no external config-file path, `--config` flag, or `CRAFTPANEL_CONFIG` variable — override values through environment variables (and `_FILE` for secrets).

## Environment variables

Each `application.conf` value can be overridden by its environment variable. Examples:

| Config file key     | Environment variable |
|---------------------|----------------------|
| `database.url`      | `DATABASE_URL`       |
| `database.password` | `DATABASE_PASSWORD`  |
| `jwt.secret`        | `JWT_SECRET`         |
| `http.port`         | `HTTP_PORT`          |

## Secrets (`_FILE` pattern)

For these secret values, a `_FILE` variant of the environment variable is supported. When `<NAME>_FILE` is set, master reads the secret from that file path (contents trimmed) instead of the plain environment variable — compatible with Docker secrets and Kubernetes secret mounts. A `_FILE` path that is set but unreadable is fatal (fail loud rather than silently fall back).

```bash
# Instead of:
DATABASE_PASSWORD=s3cr3t

# Use:
DATABASE_PASSWORD_FILE=/run/secrets/db_password
```

Supported `_FILE` variables:

| Variable                      | Description                                    |
|-------------------------------|------------------------------------------------|
| `DATABASE_PASSWORD_FILE`      | PostgreSQL password                            |
| `JWT_SECRET_FILE`             | JWT signing key                                |
| `NODE_BOOTSTRAP_TOKEN_FILE`   | Node registration bootstrap token (master and agent both read it) |
| `FORWARDING_KEY_FILE`         | AES-256 key encrypting the stored Velocity/BungeeCord forwarding secret — see [Forwarding key](#forwarding-key) |

## All configuration keys

| Key                    | Env var                           | Required | Default                 | Description                                                                             |
|------------------------|-----------------------------------|----------|-------------------------|-----------------------------------------------------------------------------------------|
| `database.url`         | `DATABASE_URL`                    | Yes      | —                       | JDBC connection string                                                                  |
| `database.username`    | `DATABASE_USERNAME`               | Yes      | —                       | Database user                                                                           |
| `database.password`    | `DATABASE_PASSWORD`               | Yes      | —                       | Database password                                                                       |
| `database.poolSize`    | `DATABASE_POOL_SIZE`              | No       | `10`                    | HikariCP maximum pool size                                                              |
| `http.port`            | `HTTP_PORT`                       | No       | `8080`                  | HTTP bind port (Ktor binds `0.0.0.0` by default; not configurable)                      |
| `grpc.port`            | `GRPC_PORT`                       | No       | `50051`                 | gRPC bind port (server uses `forPort()` without a bind address; not configurable)       |
| `grpc.certStorePath`   | `GRPC_CERT_STORE_PATH`            | No       | `/app/certs`            | Directory for auto-generated CA + server certs                                          |
| `grpc.tlsSans`         | `GRPC_TLS_SANS`                   | No       | —                       | Comma-separated extra SANs (add server hostname/IP)                                     |
| `grpc.tlsCertPath`     | `GRPC_TLS_CERT`                   | No       | —                       | BYOC: path to server cert (overrides auto-gen)                                          |
| `grpc.tlsKeyPath`      | `GRPC_TLS_KEY`                    | No       | —                       | BYOC: path to private key (required with GRPC_TLS_CERT)                                 |
| `jwt.secret`           | `JWT_SECRET`                      | Yes      | —                       | JWT signing key (min 32 bytes)                                                          |
| `cors.publicUrls`      | `PUBLIC_URLS`                     | No\*     | —                       | Comma-separated full URLs of every origin the browser calls the API from, e.g. `https://craftpanel.example.com`. \*Required outside `app.profile=dev` — with no value and a non-`dev` profile, CORS allows **no** origins and every browser request is rejected with `403`. In the bundled `docker-compose.yml`, this is set automatically from `DOMAIN`. |
| `auth.secureCookies`   | `AUTH_SECURE_COOKIES`             | No       | `true`                  | Set `Secure` flag on auth cookies (disable in dev behind plain HTTP)                    |
| `auth.cookieDomain`    | `AUTH_COOKIE_DOMAIN`              | No       | —                       | Shared parent domain for the refresh-token cookie (e.g. `.example.com`), only needed for a split-subdomain deploy — see [Split-subdomain deploy](../usage/deployment.md#split-subdomain-deploy-optional) |
| `node.bootstrapToken`  | `NODE_BOOTSTRAP_TOKEN`            | Yes      | —                       | Token agents use to register for the first time (min 16 chars)                          |
| `docker.endpoint`      | `DOCKER_ENDPOINT`                 | No       | —                       | Docker host for Swarm overlay network management (e.g. `unix:///var/run/docker.sock`). When set, master creates/deletes overlay networks for Server Networks and allows cross-node membership. |
| `forwarding.key`       | `FORWARDING_KEY` (or `_FILE`)     | Yes      | —                       | Base64-encoded 32-byte AES-256 key, encrypts the forwarding secret at rest — see [Forwarding key](#forwarding-key) |
| _(env-only)_           | `CRAFTPANEL_CONTAINER_PREFIX`     | No       | `craftpanel`            | Prefix master applies to the Docker resource names it tracks. Read directly from the environment, not from `application.conf`. Keep in sync with the agent's prefix. |

!!! note
    `rateLimit.loginPerMinute`, `rateLimit.refreshPerMinute`, and `rateLimit.totpVerifyPerMinute`
    are read by master but have **no environment-variable mapping** — they cannot be set via env.
    The user-configurable equivalents (`rate_limit_login_per_minute`,
    `rate_limit_refresh_per_minute`) are DB-backed runtime settings edited from the **Settings**
    page; see [Runtime settings](#runtime-settings-db-backed-editable-in-the-ui).

## Forwarding key

`FORWARDING_KEY` is the AES-256 key used to encrypt the Velocity/BungeeCord modern-forwarding secret before it is stored in the database (see ADR-0003 — `docs/adr/0003-backend-forwarding-secret-master-owned.md`). Master mints and owns the forwarding secret itself and pushes it to the proxy and to each eligible backend server; `FORWARDING_KEY` only protects that secret at rest — it is not the forwarding secret itself, and it is never written into any Minecraft server config.

It must be Base64-encoded 32 raw bytes. Generate one on Linux (or macOS):

```bash
openssl rand -base64 32
```

If `openssl` isn't available:

```bash
head -c 32 /dev/urandom | base64
```

Set the result as `FORWARDING_KEY` (or point `FORWARDING_KEY_FILE` at a file containing it). Master refuses to start outside `app.profile=dev` if this is left at the built-in default or isn't valid Base64 of exactly 32 bytes.

!!! warning
    Generate this once and keep it. If it's lost or changed after backends have been configured for forwarding, master can no longer decrypt the stored secret, and every proxy/backend pair using it must be reconfigured.

## Runtime settings (DB-backed, editable in the UI)

These values are stored in the `system_settings` database table and can be changed at runtime via the **Settings** page (`/settings`). They are not configurable via environment variables. Where noted, the new value takes effect after a master restart.

| Setting key                   | Default                  | Description                                                                                   |
|-------------------------------|--------------------------|-----------------------------------------------------------------------------------------------|
| `metric_retention_days`       | `30`                     | How long node/container metric samples are kept (days)                                        |
| `default_backup_max_count`    | `10`                     | Default maximum number of backups to keep per server                                          |
| `default_port_range_start`    | `25570`                  | Lower bound of the host-port pool for new servers                                             |
| `default_port_range_end`      | `26070`                  | Upper bound of the host-port pool for new servers                                             |
| `restart_max_attempts`        | `5`                      | Max consecutive crash-restarts before leaving a server UNHEALTHY (0 disables). Pushed to agents live. |
| `restart_window_seconds`      | `600`                    | Rolling window (s) for counting crashes; a gap longer than this resets the counter. Pushed to agents live. |
| `jvm_metrics_poll_interval_seconds` | `30`               | How often each running server's JVM heap is sampled (s). Pushed to agents live.               |
| `metrics_poll_interval_seconds` | `5`                    | Agent node/container metrics polling cadence (s). Pushed to agents live.                      |
| `metrics_collection_concurrency` | `8`                   | Max server containers an agent samples in parallel per metrics tick. Pushed to agents live.   |
| `agent_reconcile_interval_seconds` | `30`               | Agent convergence backstop sweep cadence (s); `0` disables the sweep. Pushed to agents live.  |
| `dns_provider`                | `none`                   | DNS provider: `none` or `cloudflare`. See [Enabling Public Hostnames](../usage/enabling-public-hostnames.md). |
| `cf_api_token`                | _(empty, write-only)_    | Cloudflare API token, required when `dns_provider=cloudflare`. Stored encrypted at rest; the API only ever returns `cf_api_token_set` (boolean). |
| `rate_limit_login_per_minute` | `10`                     | Max login attempts per client per minute. **Restart required.**                               |
| `rate_limit_refresh_per_minute` | `30`                   | Max token-refresh calls per client per minute. **Restart required.**                          |
| `image_minecraft`             | `itzg/minecraft-server`  | Base Docker image for Minecraft servers. **Restart required.**                                |
| `image_proxy`                 | `itzg/mc-proxy`          | Base Docker image for proxy servers (BungeeCord/Velocity/Waterfall). **Restart required.**    |

!!! warning
Never store deployment secrets (database credentials, the forwarding key) in the config file or the settings table in production. The Cloudflare API token is the one secret stored in the settings table, encrypted at rest with `FORWARDING_KEY`.

## Initial admin user

On a fresh database (empty users table), master will seed a Super Admin account if these variables are set:

| Variable               | Default   | Description                         |
|------------------------|-----------|-------------------------------------|
| `CRAFTPANEL_ADMIN_EMAIL`    | _(empty)_ | Email address for the initial admin |
| `CRAFTPANEL_ADMIN_PASSWORD` | _(empty)_ | Password — stored as Argon2id hash  |
| `CRAFTPANEL_ADMIN_USERNAME` | `admin`   | Username for the initial admin      |
| `CRAFTPANEL_ADMIN_RESET_PASSWORD` | `false` | Force-reset the admin password (see below) |

The seed runs **once only**: if any user already exists, these variables are ignored and master starts normally. It is safe to leave them set across container restarts.

!!! tip
Remove `CRAFTPANEL_ADMIN_EMAIL` and `CRAFTPANEL_ADMIN_PASSWORD` from your compose file after the first successful login.

### Reset the admin password

If the admin password is ever lost — or you want to force a change — set `CRAFTPANEL_ADMIN_RESET_PASSWORD=true` together with a new `CRAFTPANEL_ADMIN_PASSWORD` and restart master. On startup master:

1. Updates the admin's password hash (the user is looked up by `CRAFTPANEL_ADMIN_EMAIL`, regardless of how many users exist).
2. Sets `must_change_password=true` — the admin must pick a new password on next login and cannot use the app until they do.
3. Revokes all of the admin's refresh tokens, so existing sessions are signed out. Only a JWT access token (max 15 minutes) can survive the restart.

Remove `CRAFTPANEL_ADMIN_RESET_PASSWORD` from your compose file after the restart — the seed-only variables (`CRAFTPANEL_ADMIN_EMAIL`/`CRAFTPANEL_ADMIN_PASSWORD`) do not re-run, but leaving a one-shot reset flag set risks repeating the reset on every future restart. A warning is logged if no user matches `CRAFTPANEL_ADMIN_EMAIL`.

## Profile

| Key           | Env var             | Default | Description                                                                                                      |
|----------------|-----------------------|---------|--------------------------------------------------------------------------------------------------------------------|
| `app.profile` | `CRAFTPANEL_PROFILE` | `prod`  | `dev` relaxes CORS (`anyHost()` fallback), skips the HSTS header, and skips `AppConfig.validate()` secret-strength checks. |

## Agent configuration

The agent process (one per node) reads its own environment variables, separate from master's.

| Key                          | Env var                             | Required      | Default                       | Description                                                                                                                                 |
|-------------------------------|----------------------------------------|----------------|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| `profile`                    | `APP_PROFILE`                       | No             | `prod`                        | `dev` relaxes validation: allows a default/short `NODE_BOOTSTRAP_TOKEN` and plaintext gRPC (no CA cert required); warns instead of failing. |
| `masterAddress`               | `MASTER_HOST`                       | No             | `localhost`                   | Master hostname for the gRPC control channel                                                                                                 |
| `masterPort`                  | `MASTER_GRPC_PORT`                  | No             | `50051`                       | Master gRPC port                                                                                                                              |
| `masterHttpPort`               | `MASTER_HTTP_PORT`                  | No             | `8080`                        | Master HTTP port (used for bulk data transfer callbacks)                                                                                     |
| `tlsCertPath`                  | `GRPC_TLS_CERT`                     | No             | —                              | Explicit path to master's CA cert PEM; takes priority over the auto-fetched cert                                                              |
| `caCertFilePath`               | `GRPC_CA_CERT_FILE`                 | No             | `/app/config/grpc-ca.crt`     | Where the agent persists/reads the CA cert received from master during registration                                                          |
| `bootstrapToken`               | `NODE_BOOTSTRAP_TOKEN` (or `_FILE`) | Yes (non-dev)  | `changeme`                    | Token used for first-time node registration; must match master's value; min 16 chars outside `dev`                                          |
| `keyFilePath`                  | `NODE_KEY_FILE`                     | No             | `/app/config/node.key`        | Where the agent persists its node key after registration                                                                                     |
| `dockerSocketPath`             | `DOCKER_SOCKET`                     | No             | `unix:///var/run/docker.sock` | Docker daemon socket used to manage containers on this node                                                                                  |
| `dataBasePath`                 | `DATA_PATH`                         | No             | `/data`                       | Path inside the agent container where server data lives                                                                                       |
| `hostDataBasePath`             | `HOST_DATA_PATH`                    | No             | value of `DATA_PATH`          | Host path Docker uses when bind-mounting data into Minecraft server containers — must be an absolute path that exists on the host            |
| `serversByNameRoot`           | `SERVERS_BY_NAME_PATH`              | No             | `$DATA_PATH/servers-by-name`  | Root of the human-readable `servers-by-name/<name>` symlink overlay                                                                          |
| `backupsByServerRoot`         | `BACKUPS_BY_SERVER_PATH`            | No             | `$DATA_PATH/backups-by-server` | Root of the `backups-by-server/<name>/<timestamp>.tar.gz` symlink overlay                                                                   |
| `mcRouterImage`                | `MCROUTER_IMAGE`                    | No             | `itzg/mc-router:latest`       | Image used for the shared mc-router container                                                                                                |
| `mcRouterUpdateOnStart`        | `MCROUTER_UPDATE_ON_START`          | No             | `true`                        | Whether to pull a fresh mc-router image on agent start                                                                                        |
| `mcRouterContainerName`        | `MCROUTER_CONTAINER_NAME`           | No             | `craftpanel-mc-router`        | Overrides the mc-router container name                                                                                                        |
| `mcRouterEnabled`              | `MCROUTER_ENABLED`                  | No             | `true`                        | When `false`, the agent never provisions, attaches, detaches, or metrics-queries mc-router                                                    |
| `publicIpUrl`                  | `PUBLIC_IP_URL`                     | No             | —                              | External service used to detect this node's public IP                                                                                        |
| `hostnameOverride`             | `NODE_HOSTNAME`                     | No             | —                              | Overrides the hostname the agent reports to master (useful when container hostname differs from actual node hostname)                        |
| `systemReservedRamMb`          | `SYSTEM_RESERVED_RAM_MB`            | No             | `0`                            | RAM (MB) reserved for the host OS, excluded from server allocation capacity                                                                   |
| `systemReservedCpuMillicores`  | `SYSTEM_RESERVED_CPU_MILLICORES`    | No             | `0`                            | CPU millicores (1000 per core) reserved for the host OS, excluded from server allocation capacity                                             |
| `craftpanelNetwork`            | `CRAFTPANEL_NETWORK`                | No             | `craftpanel`                  | Docker network shared by the agent, mc-router, and rsync utility containers (not game server containers)                                       |
| `containerNamePrefix`          | `CRAFTPANEL_CONTAINER_PREFIX`       | No             | `craftpanel`                  | Prefix applied to all container names this agent creates                                                                                      |
| `privateIpOverride`            | `NODE_PRIVATE_IP`                   | No             | —                              | Overrides the private IP the agent reports to master                                                                                          |
| `publicIpOverride`             | `NODE_PUBLIC_IP`                    | No             | —                              | Overrides the public IP the agent reports to master; takes priority over `PUBLIC_IP_URL`                                                      |
| `pullMaxImageAgeHours`         | `PULL_MAX_IMAGE_AGE_HOURS`          | No             | `24`                           | Max age of a locally-cached image before a fresh pull is attempted                                                                             |

!!! note "Removed agent tuning keys"
    `metricsPollIntervalSeconds`, `metricsCollectionConcurrency` and `reconcileIntervalSeconds` were
    removed from the agent config. They are install-wide *System Settings* now, pushed by master —
    see [Runtime settings](#runtime-settings-db-backed-editable-in-the-ui).

## Frontend configuration

| Env var      | Default                 | Description                                                                                                                                                                                                                                                          |
|---------------|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MASTER_URL` | `http://localhost:8080` | Server-side base URL used by the frontend's `/healthz` route (master version check) and the `/api/*` catch-all proxy route (`app/api/[...path]/route.ts`). Both compose files set this to `http://master:8080` (the internal Docker network address) — it is never `localhost` inside a container. |
| `PUBLIC_API_URL` | — (same-origin) | Browser-facing master origin, e.g. `https://api.example.com`. Only needed for a split-subdomain deploy where the frontend and master are on different hosts/subdomains — see [Split-subdomain deploy](../usage/deployment.md#split-subdomain-deploy-optional). Leave unset for the default single-domain deploy, where the browser calls `/api` on its own origin. |
| `NEXT_PUBLIC_CRAFTPANEL_BUILD_VERSION` | `unknown` | Build-time only. The version string baked into the bundle by the Gradle build; reported by `/healthz`. Not set at runtime. |
| `DEV_ALLOWED_ORIGINS` | — | Dev-only (`next dev`): comma-separated extra origins allowed to load HMR/client chunks (e.g. a LAN IP). |
| `DEV_API_PROXY` | — | Dev-only (`next dev`): proxies `/api/*` to a running master (e.g. `http://localhost:8080`). |
