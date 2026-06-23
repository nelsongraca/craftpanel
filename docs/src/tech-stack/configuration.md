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
| `database.user`        | `DATABASE_USERNAME`               | Yes      | —                       | Database user                                                                           |
| `database.password`    | `DATABASE_PASSWORD`               | Yes      | —                       | Database password                                                                       |
| `database.poolSize`    | `DATABASE_POOL_SIZE`              | No       | `10`                    | HikariCP maximum pool size                                                              |
| `http.port`            | `HTTP_PORT`                       | No       | `8080`                  | HTTP bind port (Ktor binds `0.0.0.0` by default; not configurable)                      |
| `grpc.port`            | `GRPC_PORT`                       | No       | `50051`                 | gRPC bind port (server uses `forPort()` without a bind address; not configurable)       |
| `GRPC_CERT_STORE_PATH` | `GRPC_CERT_STORE_PATH`            | No       | `/etc/craftpanel/certs` | Directory for auto-generated CA + server certs                                          |
| `GRPC_TLS_SANS`        | `GRPC_TLS_SANS`                   | No       | —                       | Comma-separated extra SANs (add server hostname/IP)                                     |
| `GRPC_TLS_CERT`        | `GRPC_TLS_CERT`                   | No       | —                       | BYOC: path to server cert (overrides auto-gen)                                          |
| `GRPC_TLS_KEY`         | `GRPC_TLS_KEY`                    | No       | —                       | BYOC: path to private key (required with GRPC_TLS_CERT)                                 |
| `jwt.secret`           | `JWT_SECRET`                      | Yes      | —                       | JWT signing key (min 32 bytes)                                                          |
| `dns.provider`         | `DNS_PROVIDER`                    | Yes      | —                       | DNS provider identifier, e.g. `cloudflare`                                              |
| `dns.api_key`          | `CF_API_TOKEN`                    | Yes      | —                       | Cloudflare API token (DNS provider API key)                                             |
| `platform_name`        | *(system_settings DB)*            | No       | `CraftPanel`            | Platform name used in generated server MOTDs; stored in `system_settings` DB table      |
| `restart.maxAttempts`  | `CONTAINER_RESTART_MAX_ATTEMPTS`  | No       | `5`                     | Max consecutive crash-restarts before a server is left UNHEALTHY for manual action (0 disables) |
| `restart.windowSeconds`| `CONTAINER_RESTART_WINDOW_SECONDS`| No       | `600`                   | Rolling window (s) for counting consecutive crashes; a longer gap resets the counter            |

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
