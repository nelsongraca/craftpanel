# Configuration & Secrets

CraftPanel master is configured through a layered system: a bundled HOCON config file provides defaults, environment variables override any default, and sensitive values can be supplied as mounted secret files.

## Precedence

For secrets (`JWT_SECRET`, `DATABASE_PASSWORD`, `CF_API_TOKEN`, `NODE_BOOTSTRAP_TOKEN`):

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
| `dns.api_key`       | `CF_API_TOKEN`       |
| `http.port`         | `HTTP_PORT`          |

## Secrets (`_FILE` pattern)

For the four secret values, a `_FILE` variant of the environment variable is supported. When `<NAME>_FILE` is set, master reads the secret from that file path (contents trimmed) instead of the plain environment variable — compatible with Docker secrets and Kubernetes secret mounts. A `_FILE` path that is set but unreadable is fatal (fail loud rather than silently fall back).

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
| `CF_API_TOKEN_FILE`           | DNS provider (Cloudflare) API key              |
| `NODE_BOOTSTRAP_TOKEN_FILE`   | Node registration bootstrap token (master and agent both read it) |

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
| `dns.provider`         | `DNS_PROVIDER`                    | No       | `none`                  | DNS provider identifier, e.g. `cloudflare`                                              |
| `dns.cloudflare.apiToken` | `CF_API_TOKEN`                 | No       | —                       | Cloudflare API token (required when `dns.provider=cloudflare`)                          |
| `cors.allowedSchemes`  | `CORS_ALLOWED_SCHEMES`            | No       | `https`                 | Comma-separated allowed CORS schemes                                                    |
| `auth.secureCookies`   | `AUTH_SECURE_COOKIES`             | No       | `true`                  | Set `Secure` flag on auth cookies (disable in dev behind plain HTTP)                    |
| `node.bootstrapToken`  | `NODE_BOOTSTRAP_TOKEN`            | Yes      | —                       | Token agents use to register for the first time (min 16 chars)                          |
| `node.agentDataPort`   | `AGENT_DATA_PORT`                 | No       | `50052`                 | Port for agent bulk-data transfers                                                      |

## Runtime settings (DB-backed, editable in the UI)

These values are stored in the `system_settings` database table and can be changed at runtime via the **Settings** page (`/settings`). They are not configurable via environment variables. Where noted, the new value takes effect after a master restart.

| Setting key                   | Default                  | Description                                                                                   |
|-------------------------------|--------------------------|-----------------------------------------------------------------------------------------------|
| `metric_retention_days`       | `30`                     | How long node/container metric samples are kept (days)                                        |
| `default_backup_max_count`    | `10`                     | Default maximum number of backups to keep per server                                          |
| `default_port_range_start`    | `25570`                  | Lower bound of the host-port pool for new servers                                             |
| `default_port_range_end`      | `26070`                  | Upper bound of the host-port pool for new servers                                             |
| `restart_max_attempts`        | `5`                      | Max consecutive crash-restarts before leaving a server UNHEALTHY (0 disables). **Restart required.** |
| `restart_window_seconds`      | `600`                    | Rolling window (s) for counting crashes; a gap longer than this resets the counter. **Restart required.** |
| `rate_limit_login_per_minute` | `10`                     | Max login attempts per client per minute. **Restart required.**                               |
| `rate_limit_refresh_per_minute` | `30`                   | Max token-refresh calls per client per minute. **Restart required.**                          |
| `image_minecraft`             | `itzg/minecraft-server`  | Base Docker image for Minecraft servers. **Restart required.**                                |
| `image_proxy`                 | `itzg/mc-proxy`          | Base Docker image for proxy servers (BungeeCord/Velocity/Waterfall). **Restart required.**    |

!!! warning
Never store secrets in the config file in production. Use environment variables or the `_FILE` secret pattern instead.

## Initial admin user

On a fresh database (empty users table), master will seed a Super Admin account if these variables are set:

| Variable               | Default   | Description                         |
|------------------------|-----------|-------------------------------------|
| `CRAFTPANEL_ADMIN_EMAIL`    | _(empty)_ | Email address for the initial admin |
| `CRAFTPANEL_ADMIN_PASSWORD` | _(empty)_ | Password — stored as Argon2id hash  |
| `CRAFTPANEL_ADMIN_USERNAME` | `admin`   | Username for the initial admin      |

The seed runs **once only**: if any user already exists, these variables are ignored and master starts normally. It is safe to leave them set across container restarts.

!!! tip
Remove `CRAFTPANEL_ADMIN_EMAIL` and `CRAFTPANEL_ADMIN_PASSWORD` from your compose file after the first successful login.
