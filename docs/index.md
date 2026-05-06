# CraftPanel

CraftPanel is a web-based Minecraft server management platform that wraps [`itzg/minecraft-server`](https://github.com/itzg/docker-minecraft-server) and [`itzg/mc-proxy`](https://github.com/itzg/docker-minecraft-bedrock-server) Docker containers. It provides a multi-user interface for creating, configuring, monitoring, and operating Minecraft server instances across one or more physical or virtual nodes, with role-based access control and a UI suitable for non-technical users.

## Design Goals

- Single-page web UI — no client software required
- Multi-node horizontal scaling — servers distributed across nodes by an administrator
- No game traffic routed through the management backend
- Full lifecycle management: create, configure, start, stop, backup, migrate, delete
- Permission system flexible enough to support teams with different trust levels
- Opinionated defaults with escape hatches — managed config mode with manual override available

## Key Terminology

| Term | Description |
|---|---|
| **Master** | Central backend service; source of truth for all nodes, users, and server state |
| **Node** | A machine running the CraftPanel agent and Docker; hosts server containers |
| **Agent** | Lightweight service on each node; executes instructions from master, streams metrics |
| **Server** | A single itzg container instance (game server or proxy) |
| **Server Network** | A group of servers (backends + optional proxy) that form one logical Minecraft network |
| **mc-router** | itzg's purpose-built Minecraft TCP router; one instance per node for player ingress |

## High-Level Architecture

```
Browser
  │  HTTPS + WSS
  ▼
Master Backend  (Kotlin + Ktor, PostgreSQL)
  │  gRPC over TLS
  ├──▶ Node Agent 1  ──▶  Docker  ──▶  [mc-router] [server] [server] [proxy]
  └──▶ Node Agent 2  ──▶  Docker  ──▶  [mc-router] [server] [server]

Players ──▶ mc-router (port 25565, per node) ──▶ containers (Docker bridge or host port)
```

Master is the sole source of truth. Agents execute instructions and stream data back. Game traffic never passes through master.
