# Configuration & Secrets

CraftPanel master is configured through a layered system: a config file provides defaults, environment variables override any file value, and sensitive values can be supplied as mounted secret files.

## Precedence

```
Secret file (_FILE env var)  ← highest priority
  ↓
Environment variable
  ↓
Config file (craftpanel.conf / application.yaml)
  ↓
Built-in default              ← lowest priority
```

## Config file

The config file is `craftpanel.conf` (YAML format), located at a path passed via the `--config` flag or the `CRAFTPANEL_CONFIG` environment variable. Example:

```yaml
database:
  url: jdbc:postgresql://localhost:5432/craftpanel
  username: craftpanel

http:
  port: 8080

grpc:
  port: 50051
  # cert_store: /etc/craftpanel/certs   # where auto-generated CA+server certs are persisted
  # tls_sans: master.example.com,10.0.0.1  # extra SANs for auto-generated cert
  # Bring-your-own-cert (overrides auto-gen):
  # tls:
  #   cert: /etc/craftpanel/tls/grpc.crt
  #   key: /etc/craftpanel/tls/grpc.key

jwt:
  # key loaded from secret file in production — see below
  secret: changeme

dns:
  provider: cloudflare
  # api_key loaded from secret file in production
```

## Environment variables

Every config file key can be overridden by an environment variable using the key name uppercased with underscores. The config file uses HOCON `${?ENV_VAR}` syntax, where `?` means the variable is optional. Examples of documented env vars:

| Config file key     | Environment variable |
|---------------------|----------------------|
| `database.url`      | `DATABASE_URL`       |
| `database.password` | `DATABASE_PASSWORD`  |
| `jwt.secret`        | `JWT_SECRET`         |
| `dns.api_key`       | `CF_API_TOKEN`       |
| `http.port`         | `HTTP_PORT`          |

## Secrets (`_FILE` pattern)

For sensitive values, a `_FILE` variant of any environment variable is supported. When set, master reads the value from the specified file path rather than the environment variable directly. This is
compatible with Docker secrets and Kubernetes secret mounts.

```bash
# Instead of:
DATABASE_PASSWORD=s3cr3t

# Use:
DATABASE_PASSWORD_FILE=/run/secrets/db_password
```

Supported `_FILE` variables:

| Variable                      | Description          |
|-------------------------------|----------------------|
| `DATABASE_PASSWORD_FILE`      | PostgreSQL password  |
| `JWT_SECRET_FILE`             | JWT signing key      |
| `CF_API_TOKEN_FILE`           | DNS provider API key |

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
