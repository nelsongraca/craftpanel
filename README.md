# CraftPanel

Self-hosted Minecraft server management platform. Manage servers across multiple nodes from a single web UI, with role-based access control, live console streaming, backups, migration, and Modrinth
mod integration.

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
      - "8080:8080"     # REST API + WebSocket
      - "50051:50051"   # gRPC (agents connect here)
    environment:
      DATABASE_URL: jdbc:postgresql://db:5432/craftpanel
      DATABASE_USERNAME: craftpanel
      DATABASE_PASSWORD: changeme
      JWT_SECRET: "replace-with-a-random-32-char-string"
      NODE_BOOTSTRAP_TOKEN: "replace-with-a-secret-token"
      # gRPC TLS — required in production; agents verify master with this cert.
      # Omit to run plaintext (development only, master will log a warning).
      GRPC_TLS_CERT: /run/secrets/grpc.crt
      GRPC_TLS_KEY: /run/secrets/grpc.key
    volumes:
      - ./certs/grpc.crt:/run/secrets/grpc.crt:ro
      - ./certs/grpc.key:/run/secrets/grpc.key:ro
    depends_on:
      - db

  frontend:
    image: ghcr.io/nelsongraca/craftpanel/frontend:latest
    ports:
      - "3000:3000"
    environment:
      NEXT_PUBLIC_API_URL: http://master:8080

volumes:
  postgres_data:
```

```bash
docker compose up -d
```

Open `http://localhost:3000`.

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
      GRPC_TLS_CERT: /run/secrets/grpc.crt
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - node_key:/etc/craftpanel
      - ./certs/grpc.crt:/run/secrets/grpc.crt:ro
    group_add:
      - "999"   # docker group GID — adjust to match host

volumes:
  node_key:
```

On first start the agent registers itself with master using the bootstrap token. A Super Admin must approve the node in the UI before it can host servers.

### Environment variable reference

**Master**

| Variable               | Default                                       | Description                                     |
|------------------------|-----------------------------------------------|-------------------------------------------------|
| `DATABASE_URL`         | `jdbc:postgresql://localhost:5432/craftpanel` | PostgreSQL JDBC URL                             |
| `DATABASE_USERNAME`    | `craftpanel`                                  | Database user                                   |
| `DATABASE_PASSWORD`    | _(empty)_                                     | Database password                               |
| `JWT_SECRET`           | `changeme-at-least-32-characters-long`        | HMAC secret for JWT signing                     |
| `NODE_BOOTSTRAP_TOKEN` | `changeme`                                    | Shared secret for initial node registration     |
| `HTTP_PORT`            | `8080`                                        | REST/WebSocket listen port                      |
| `GRPC_PORT`            | `50051`                                       | gRPC listen port                                |
| `GRPC_TLS_CERT`        | _(empty)_                                     | Path to TLS certificate (disables TLS if unset) |
| `GRPC_TLS_KEY`         | _(empty)_                                     | Path to TLS private key                         |

**Agent**

| Variable               | Default                       | Description                                                            |
|------------------------|-------------------------------|------------------------------------------------------------------------|
| `MASTER_HOST`          | `localhost`                   | Master hostname or IP                                                  |
| `MASTER_GRPC_PORT`     | `50051`                       | Master gRPC port                                                       |
| `NODE_BOOTSTRAP_TOKEN` | `changeme`                    | Must match master's `NODE_BOOTSTRAP_TOKEN`                             |
| `NODE_KEY_FILE`        | `/etc/craftpanel/node.key`    | Where the node key is persisted after registration                     |
| `GRPC_TLS_CERT`        | _(empty)_                     | Path to master's TLS cert for verification (leave blank for plaintext) |
| `DOCKER_SOCKET`        | `unix:///var/run/docker.sock` | Docker socket path                                                     |

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
