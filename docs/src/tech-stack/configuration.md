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
  user: craftpanel

http:
  host: 0.0.0.0
  port: 8080

grpc:
  host: 0.0.0.0
  port: 9090
  tls:
    cert: /etc/craftpanel/tls/grpc.crt
    key: /etc/craftpanel/tls/grpc.key

jwt:
  # key loaded from secret file in production — see below
  secret: changeme

dns:
  provider: cloudflare
  base_domain: mc.example.com
  # api_key loaded from secret file in production
```

## Environment variables

Every config file key has a corresponding environment variable using the pattern `CRAFTPANEL_<SECTION>_<KEY>` in uppercase with underscores. Examples:

| Config file key | Environment variable |
|---|---|
| `database.url` | `CRAFTPANEL_DATABASE_URL` |
| `database.user` | `CRAFTPANEL_DATABASE_USER` |
| `database.password` | `CRAFTPANEL_DATABASE_PASSWORD` |
| `jwt.secret` | `CRAFTPANEL_JWT_SECRET` |
| `dns.api_key` | `CRAFTPANEL_DNS_API_KEY` |
| `http.port` | `CRAFTPANEL_HTTP_PORT` |

## Secrets (`_FILE` pattern)

For sensitive values, a `_FILE` variant of any environment variable is supported. When set, master reads the value from the specified file path rather than the environment variable directly. This is compatible with Docker secrets and Kubernetes secret mounts.

```bash
# Instead of:
CRAFTPANEL_DATABASE_PASSWORD=s3cr3t

# Use:
CRAFTPANEL_DATABASE_PASSWORD_FILE=/run/secrets/db_password
```

Supported `_FILE` variables:

| Variable | Description |
|---|---|
| `CRAFTPANEL_DATABASE_PASSWORD_FILE` | PostgreSQL password |
| `CRAFTPANEL_JWT_SECRET_FILE` | JWT signing key |
| `CRAFTPANEL_DNS_API_KEY_FILE` | DNS provider API key |

## All configuration keys

| Key | Env var | Required | Default | Description |
|---|---|---|---|---|
| `database.url` | `CRAFTPANEL_DATABASE_URL` | Yes | — | JDBC connection string |
| `database.user` | `CRAFTPANEL_DATABASE_USER` | Yes | — | Database user |
| `database.password` | `CRAFTPANEL_DATABASE_PASSWORD` | Yes | — | Database password |
| `http.host` | `CRAFTPANEL_HTTP_HOST` | No | `0.0.0.0` | HTTP bind address |
| `http.port` | `CRAFTPANEL_HTTP_PORT` | No | `8080` | HTTP bind port |
| `grpc.host` | `CRAFTPANEL_GRPC_HOST` | No | `0.0.0.0` | gRPC bind address |
| `grpc.port` | `CRAFTPANEL_GRPC_PORT` | No | `9090` | gRPC bind port |
| `grpc.tls.cert` | `CRAFTPANEL_GRPC_TLS_CERT` | Yes | — | Path to gRPC TLS certificate |
| `grpc.tls.key` | `CRAFTPANEL_GRPC_TLS_KEY` | Yes | — | Path to gRPC TLS private key |
| `jwt.secret` | `CRAFTPANEL_JWT_SECRET` | Yes | — | JWT signing key (min 32 bytes) |
| `dns.provider` | `CRAFTPANEL_DNS_PROVIDER` | Yes | — | DNS provider identifier, e.g. `cloudflare` |
| `dns.api_key` | `CRAFTPANEL_DNS_API_KEY` | Yes | — | DNS provider API key |
| `dns.base_domain` | `CRAFTPANEL_DNS_BASE_DOMAIN` | Yes | — | Base domain, e.g. `mc.example.com` |

!!! warning
    Never store secrets in the config file in production. Use environment variables or the `_FILE` secret pattern instead.
