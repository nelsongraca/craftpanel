# CraftPanel

Self-hosted Minecraft server management platform. Manage servers across multiple nodes from a single web UI, with role-based access control, live console streaming, backups, migration, and Modrinth
mod integration.

## Agentic Development Experiment

CraftPanel is also an experiment in agentic software development. The goal is to build the project as close to **zero human-authored code** as practically achievable, using [Claude Code](https://claude.ai/code) as the primary development agent throughout the entire lifecycle ? architecture, scaffolding, implementation, and iteration.

Human involvement is intentionally limited to:

- **Specification and direction** - defining requirements, reviewing designs, and course-correcting the agent
- **Code review** - agent-produced code is periodically reviewed by a human; not exhaustively, but enough to catch structural drift
- **Judgement calls** - security decisions, architectural trade-offs, and anything the agent flags as ambiguous

The codebase is therefore a living record of what agentic development can produce on a non-trivial, production-scoped project. Shortcuts were not taken in the spec to make the agent's job easier ? the full requirements, data model, API surface, and UI are designed to the same standard as a human-built product.

> This README is itself agent-written, guided by human direction, consistent with the experiment's own rules.

## Overview

```
Browser
  │  HTTPS + WSS
  ▼
Master Backend  (Kotlin + Ktor, PostgreSQL)
  │  gRPC over TLS
  ├──▶ Node Agent 1  ──▶  Docker  ──▶  [mc-router] [server] [server] [proxy]
  └──▶ Node Agent 2  ──▶  Docker  ──▶  [mc-router] [server] [server]

Players ──▶ mc-router (port 25565, per node) ──▶ containers
```

- **Master** — central backend; REST API, WebSocket broker, gRPC server, JWT auth, PostgreSQL
- **Agent** — lightweight service on each node; executes commands from master, streams metrics, manages Docker containers
- **Frontend** — Next.js 16 single-page app with typed API client generated from OpenAPI spec

Game traffic never passes through master. Agents initiate outbound gRPC connections, so nodes behind NAT require no inbound firewall rules.

## Features

- Multi-node server distribution across physical or virtual nodes
- Full server lifecycle: create, configure, start, stop, restart, delete
- Live console streaming and file explorer over WebSocket
- Backup scheduling with configurable retention
- Live rsync-based server migration between nodes
- Modrinth mod integration
- Permission-node access control with group assignments scoped to global, network, or individual server
- Cloudflare DNS integration for per-server hostnames
- Node registration with admin approval workflow

## Stack

| Layer          | Technology                                                            |
|----------------|-----------------------------------------------------------------------|
| Frontend       | Next.js 16, Tailwind CSS v4, shadcn/ui                                |
| Backend        | Kotlin 2.3, Ktor 3.4, PostgreSQL, Exposed ORM 1.2                     |
| Auth           | JWT (HS256, 15 min) + HttpOnly refresh tokens                         |
| Agent ↔ Master | gRPC 1.75 over TLS, bidirectional streaming                           |
| Containers     | Docker via `itzg/minecraft-server`, `itzg/mc-proxy`, `itzg/mc-router` |
| Build          | Gradle (no Makefile, no multi-stage Dockerfiles)                      |

## Project Structure

```
craftpanel/
├── master/     # Ktor REST/WebSocket backend + gRPC server
├── agent/      # Kotlin gRPC client, manages Docker on remote nodes
├── frontend/   # Next.js 16 frontend
├── proto/      # Shared protobuf definitions
└── docs/       # MkDocs documentation
```

## Building

Prerequisites: JDK 25, Node 22, Docker.

```bash
# Build all modules
./gradlew :master:installDist
./gradlew :frontend:assembleFrontend   # also runs OpenAPI codegen

# Build Docker images
./gradlew dockerBuildAll

# Version defaults to mod_version in gradle.properties; override for releases
./gradlew dockerBuildAll -PimageVersion=1.2.0
./gradlew dockerPushAll  -PimageVersion=1.2.0
```

### API codegen

The frontend uses a typed client generated from the backend's OpenAPI spec.

```bash
./gradlew :master:generateOpenApiSpec   # writes openapi.json at repo root
./gradlew :frontend:generateApiTypes    # generates frontend/lib/generated/
```

Both run automatically as part of `:frontend:assembleFrontend`.

## Running

### Docker Compose (recommended)

The example below runs master, frontend, and a PostgreSQL database on one machine. Agents run separately on each node machine (see [Agent on node machines](#agent-on-node-machines)).

```yaml
# docker-compose.yml
services:
  traefik:
    image: traefik:v3
    command:
      - "--providers.docker=true"
      - "--providers.docker.exposedbydefault=false"
      - "--entrypoints.web.address=:80"
    ports:
      - "80:80"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro

  db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: craftpanel
      POSTGRES_USER: craftpanel
      POSTGRES_PASSWORD: changeme
    volumes:
      - postgres_data:/var/lib/postgresql/data

  master:
    image: ghcr.io/nelsongraca/craftpanel/master:latest
    ports:
      - "50051:50051"   # gRPC — agents connect here directly
    environment:
      DATABASE_URL: jdbc:postgresql://db:5432/craftpanel
      DATABASE_USERNAME: craftpanel
      DATABASE_PASSWORD: changeme
      JWT_SECRET: "replace-with-a-random-32-char-string"
      NODE_BOOTSTRAP_TOKEN: "replace-with-a-secret-token"
      # GRPC_TLS_SANS — comma-separated hostnames/IPs agents use to reach this master.
      # Add your server's public hostname or IP so agents can verify the TLS cert.
      GRPC_TLS_SANS: "master.example.com,192.168.1.10"
    volumes:
      # Master auto-generates a self-signed CA + server cert on first boot and
      # persists them here. Mount a volume so certs survive container restarts.
      - master_certs:/etc/craftpanel/certs
    depends_on:
      - db
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.master-api.rule=PathPrefix(`/api`)"
      - "traefik.http.routers.master-api.entrypoints=web"
      - "traefik.http.routers.master-api.priority=10"
      - "traefik.http.services.master.loadbalancer.server.port=8080"

  frontend:
    image: ghcr.io/nelsongraca/craftpanel/frontend:latest
    depends_on:
      - master
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.frontend.rule=PathPrefix(`/`)"
      - "traefik.http.routers.frontend.entrypoints=web"
      - "traefik.http.routers.frontend.priority=1"
      - "traefik.http.services.frontend.loadbalancer.server.port=3000"

volumes:
  postgres_data:
  master_certs:
```

```bash
docker compose up -d
```

Open `http://localhost`.

### First-time admin user

On a fresh database, set these env vars on `master` to seed the initial Super Admin account at startup:

```yaml
environment:
  CRAFTPANEL_ADMIN_EMAIL: admin@example.com
  CRAFTPANEL_ADMIN_PASSWORD: "change-me-immediately"
  CRAFTPANEL_ADMIN_USERNAME: admin   # optional, defaults to "admin"
```

The seed runs **only when the users table is empty**. Once any user exists, these variables are ignored — safe to leave set across restarts. Remove them from the compose file after first login.

### TLS setup — distributing the CA certificate

Master generates a self-signed CA and server certificate on first boot. Before connecting agents you need to copy the CA certificate to each agent node:

```bash
# Copy the CA cert from the running master container
docker compose cp master:/etc/craftpanel/certs/grpc-ca.crt ./grpc-ca.crt

# Then copy grpc-ca.crt to each node machine and mount it into the agent container (see below)
```

The CA certificate is public — it contains no secret material. Once you have it on the node machine, agents use it to verify master's TLS certificate on every connection.

If you replace or regenerate the CA cert on master, copy the new `grpc-ca.crt` to all nodes and restart agents.

### Agent on node machines

Run one agent container per node. It needs access to the Docker socket and must be able to reach master's gRPC port (default `50051`).

```yaml
# docker-compose.agent.yml
services:
  agent:
    image: ghcr.io/nelsongraca/craftpanel/agent:latest
    environment:
      MASTER_HOST: "master.example.com"
      MASTER_GRPC_PORT: "50051"
      NODE_BOOTSTRAP_TOKEN: "replace-with-a-secret-token"   # must match master
      NODE_KEY_FILE: /etc/craftpanel/node.key               # persisted across restarts
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - node_data:/etc/craftpanel
      # CA cert copied from master — see TLS setup section above
      - ./grpc-ca.crt:/etc/craftpanel/grpc-ca.crt:ro
    group_add:
      - "999"   # docker group GID — adjust to match host

volumes:
  node_data:
```

On first start the agent registers itself with master using the bootstrap token. A Super Admin must approve the node in the UI before it can host servers.

> **Bring your own certificate:** If you prefer to manage TLS certs externally (e.g. from a corporate CA), set `GRPC_TLS_CERT` and `GRPC_TLS_KEY` on master and mount `GRPC_TLS_CERT` on agents as before. Explicit cert paths take priority over auto-gen.

### Environment variable reference

**Master**

| Variable                     | Default                                       | Description                                                                       |
|------------------------------|-----------------------------------------------|-----------------------------------------------------------------------------------|
| `DATABASE_URL`               | `jdbc:postgresql://localhost:5432/craftpanel` | PostgreSQL JDBC URL                                                               |
| `DATABASE_USERNAME`          | `craftpanel`                                  | Database user                                                                     |
| `DATABASE_PASSWORD`          | _(empty)_                                     | Database password                                                                 |
| `JWT_SECRET`                 | `changeme-at-least-32-characters-long`        | HMAC secret for JWT signing                                                       |
| `NODE_BOOTSTRAP_TOKEN`       | `changeme`                                    | Shared secret for initial node registration                                       |
| `HTTP_PORT`                  | `8080`                                        | REST/WebSocket listen port                                                        |
| `GRPC_PORT`                  | `50051`                                       | gRPC listen port                                                                  |
| `GRPC_CERT_STORE_PATH`       | `/etc/craftpanel/certs`                       | Directory for auto-generated CA + server certs (mount a volume here)             |
| `GRPC_TLS_SANS`              | _(empty)_                                     | Comma-separated extra SANs for auto-generated cert (add your server hostname/IP) |
| `GRPC_TLS_CERT`              | _(empty)_                                     | Path to TLS server cert — overrides auto-gen when set (BYOC mode)                |
| `GRPC_TLS_KEY`               | _(empty)_                                     | Path to TLS private key — required when `GRPC_TLS_CERT` is set                   |
| `CRAFTPANEL_ADMIN_EMAIL`     | _(empty)_                                     | Seed initial Super Admin email — only used when users table is empty              |
| `CRAFTPANEL_ADMIN_PASSWORD`  | _(empty)_                                     | Seed initial Super Admin password (Argon2id-hashed at startup)                   |
| `CRAFTPANEL_ADMIN_USERNAME`  | `admin`                                       | Seed initial Super Admin username                                                 |

**Agent**

| Variable               | Default                          | Description                                                                       |
|------------------------|----------------------------------|-----------------------------------------------------------------------------------|
| `MASTER_HOST`          | `localhost`                      | Master hostname or IP                                                             |
| `MASTER_GRPC_PORT`     | `50051`                          | Master gRPC port                                                                  |
| `NODE_BOOTSTRAP_TOKEN` | `changeme`                       | Must match master's `NODE_BOOTSTRAP_TOKEN`                                        |
| `NODE_KEY_FILE`        | `/etc/craftpanel/node.key`       | Where the node key is persisted after registration                                |
| `GRPC_CA_CERT_FILE`    | `/etc/craftpanel/grpc-ca.crt`    | Path to master's CA cert PEM (copy from master's `GRPC_CERT_STORE_PATH`)         |
| `GRPC_TLS_CERT`        | _(empty)_                        | Explicit CA cert path — overrides `GRPC_CA_CERT_FILE` when set (BYOC mode)       |
| `DOCKER_SOCKET`        | `unix:///var/run/docker.sock`    | Docker socket path                                                                |

## Running Tests

```bash
./gradlew :master:test
./gradlew :master:koverHtmlReport   # coverage report
```

## Documentation

Full requirements, data model, API reference, and architecture docs are in `/docs`. Build with:

```bash
cd docs && pip install mkdocs-material && mkdocs serve
```

## License

See [LICENSE](LICENSE).
