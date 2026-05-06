# Tech Stack

## Components

| Component | Technology | Notes |
|---|---|---|
| Frontend | Next.js + shadcn/ui + Tailwind | REST for data, WebSocket for console and live metrics |
| Master Backend | Kotlin + Ktor | REST API, WebSocket broker, gRPC server for agents, JWT auth |
| Database | PostgreSQL + Exposed ORM | Hosted on master node; sole persistent store |
| Node Agent | Kotlin + Ktor (minimal) | gRPC client to master, Docker socket access, `/proc` metrics, console proxy |
| Container Runtime | Docker (Docker Java SDK) | Managed by agent on each node |
| Game Servers | `itzg/minecraft-server` | All non-proxy server types |
| Proxy Servers | `itzg/mc-proxy` | Velocity, BungeeCord, Waterfall |
| Player Ingress | `itzg/mc-router` | One instance per node, Docker label-driven routing |
| DNS Management | Cloudflare API (default) | Dynamic A records for exposed servers; pluggable provider interface |

## Master ↔ Agent Communication

Master and agents communicate over **gRPC with TLS**. The agent initiates a persistent bidirectional connection to master on startup, which means nodes behind NAT do not require inbound firewall rules.

| Stream | Direction | RPC type |
|---|---|---|
| Metric streaming | Agent → Master | Server-side streaming |
| Console attach | Bidirectional | Bidirectional streaming |
| Lifecycle commands (start / stop / create / backup / rsync) | Master → Agent | Unary |

The agent-initiated connection model means:

- Nodes can be behind NAT or firewalls with no inbound rules
- Master pushes commands on the same persistent connection
- If an agent disconnects, master marks the node degraded and queues commands for reconnect

## Frontend ↔ Master Communication

- **REST** — all CRUD operations (servers, nodes, users, backups, mod lists, etc.)
- **WebSocket** — live console output, real-time metric charts, server status updates
