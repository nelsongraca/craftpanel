# Ubiquitous Language

## Infrastructure

| Term | Definition | Aliases to avoid |
|------|-----------|------------------|
| **Node** | A physical or virtual machine running the CraftPanel Agent and Docker daemon | Host, machine, server (ambiguous) |
| **Agent** | The lightweight Kotlin process running on a node that manages Docker containers on behalf of master | Worker, daemon, sidecar |
| **Master** | The central Kotlin/Ktor backend that orchestrates all nodes, stores state, and serves the REST/WebSocket API | Controller, server (ambiguous), backend |
| **Network** | A named group of servers within CraftPanel used as a permission scope boundary | Cluster, realm, group (ambiguous) |

## Server lifecycle

| Term | Definition | Aliases to avoid |
|------|-----------|------------------|
| **Server** | A managed Minecraft game server instance, represented in the DB and mapped 1-to-1 with a Docker container when running | Instance, process, game server |
| **Container** | The Docker container that runs a single server's Minecraft process | Pod, process |
| **Server Type** | The Minecraft server software variant (e.g. `PAPER`, `VANILLA`, `FABRIC`) — determines the itzg image configuration | Flavour, variant |
| **itzg Image Tag** | The Docker image tag of `itzg/minecraft-server` (e.g. `latest`, `java21`, `2024-11-01`) — controls the tooling version, not the Minecraft version | Version (ambiguous), image version |
| **Minecraft Version** | The Minecraft game version string injected as the `VERSION` env var into the container (e.g. `1.21.4`) | itzg tag (different concept) |
| **Needs Recreate** | A deferred flag on a server record indicating the container must be removed and recreated on next start, because a property requiring full recreation has changed | Restart, rebuild |
| **Status** | The current runtime state of a server: `STOPPED`, `STARTING`, `HEALTHY`, `STOPPING`, `UNHEALTHY` | State, condition, health |
| **Managed Hostname** | The hostname CraftPanel provisions and owns for an exposed server: `dnsRecordName` if a DNS record already exists, else `publicSubdomain` + the resolved domain suffix | Panel hostname, subdomain hostname |
| **Custom Hostname** | A user-supplied external hostname pointing at a server, validated against RFC-1123 and checked for collisions with other servers' hostnames and panel-managed domain suffixes | Vanity hostname, alias |
| **Canonical Hostname** | The hostname shown in the API for a server: the Custom Hostname if set, else the Managed Hostname | Public hostname (ambiguous), display hostname |
| **mc-router Label** | The Docker label attached to a server's container instructing mc-router which hostname(s) to route to it — Managed and Custom Hostname comma-joined | Router label, routing hostname |

## Access control

| Term | Definition | Aliases to avoid |
|------|-----------|------------------|
| **User** | An authenticated human identity in the system with an email, username, and password | Account, login, member |
| **Group** | A named collection of permission nodes that can be assigned to users | Role, team |
| **System Group** | A built-in group (`Super Admin`, `Server Admin`, `Operator`, `Viewer`) that cannot be deleted or renamed | Default role, built-in role |
| **Permission Node** | A dot-separated capability string (e.g. `server.start`, `system.users`) that grants a specific action | Permission, right, privilege |
| **Assignment** | A binding of a user to a group, scoped to GLOBAL, a specific SERVER, or a NETWORK | Role assignment, membership |
| **Scope** | The breadth of an assignment: `GLOBAL` (all resources), `SERVER` (one server), or `NETWORK` (all servers in a network) | Level, tier |

## Data transfer

| Term | Definition | Aliases to avoid |
|------|-----------|------------------|
| **Control Stream** | The persistent bidirectional gRPC stream between master and agent, used for lifecycle commands, console, file ops, and metrics | Main stream, command channel |
| **Bulk Data Service** | A separate agent-initiated gRPC connection used only for large file transfers (upload/download), isolated to prevent head-of-line blocking | File stream, transfer channel |
| **Node State Snapshot** | The first message an agent sends on every (re)connect, containing all known container states for master to reconcile | Sync message, hello message |
| **Node Key** | A 256-bit secret issued to an agent after registration, used for all subsequent authentication — stored as SHA-256 hash in DB | API key, token, node token |
| **Bootstrap Token** | A shared secret configured in master that any agent uses for first-time registration only | Registration token, initial secret |

## Migrations & backups

| Term | Definition | Aliases to avoid |
|------|-----------|------------------|
| **Migration** | The operation of moving a server's data from one node to another using rsync | Transfer, move |
| **Backup** | A point-in-time archive of a server's data directory, stored on the node | Snapshot, archive |
| **Migration Step** | One discrete phase of a migration (e.g. initial sync, cutover, cleanup), tracked individually | Stage, phase |

## Proxy & routing

| Term | Definition | Aliases to avoid |
|------|-----------|------------------|
| **Proxy** | A Server whose Server Type is `VELOCITY`, `BUNGEECORD`, or `WATERFALL` — it fronts other servers and routes players to them rather than running a world itself | Gateway, router (ambiguous with mc-router) |
| **Backend Server** | A (non-proxy) Server that a Proxy routes players to, identified within one Proxy by a **Backend Name** and an order | Backend (ambiguous — reserved as Master alias), downstream, child server |
| **Backend Name** | The proxy-local identifier for a Backend Server (e.g. `lobby`, `factions`) — the name that appears in the proxy's `[servers]`/`servers:` block and in the player's `/server <name>` command | Server alias, route name |
| **Proxy Config** | The generated config file written into a Proxy's data dir listing its Backend Servers — `velocity.toml` for Velocity, `config.yml` for BungeeCord/Waterfall. Rendered by master, written by the agent (ADR-0002) | Proxy settings, route table |
| **Forwarding Mode** | How a Proxy passes player identity to its Backend Servers: `none`, `legacy` (BungeeCord ip_forward), `modern` (Velocity), or `bungeeguard`. Admin-chosen per Proxy; `modern` requires backend support (Paper-lineage only) | Auth mode, forwarding type |
| **Forwarding Secret** | The shared 32-char string authenticating `modern` forwarding between a Proxy and its Backend Servers. **Master-minted and master-owned** (ADR-0003), stored encrypted-at-rest with a key held outside the DB, written to both the Proxy (`forwarding.secret`) and each eligible backend (`paper-global.yml`) | Velocity secret, forwarding.secret |
| **Backend Forwarding Config** | The forwarding config master writes into a Backend Server so it accepts forwarded players — `paper-global.yml proxies.velocity.*` (modern) or `settings.yml bungeecord: true` (legacy), always with `online-mode=false` (ADR-0003, #44) | Backend proxy config |

## Relationships

- A **Node** hosts zero or more **Servers**; each **Server** belongs to exactly one **Node**
- A **Proxy** routes to zero or more **Backend Servers**; the lowest-order backend is the default landing server (Velocity `try` / Bungee `priorities`), the rest reached via `/server <Backend Name>`
- Editing a **Proxy**'s **Backend Servers** re-renders the **Proxy Config** and sets **Needs Recreate** on the **Proxy** — it is not force-restarted
- Setting a **Forwarding Mode** on a **Proxy** (or adding a **Backend Server** to an already-forwarding **Proxy**) writes **Backend Forwarding Config** + the **Forwarding Secret** into each eligible **Backend Server** and sets **Needs Recreate** on those backends — a 1→N write across Backend Server rows (ADR-0003, #44). Backends that cannot support the mode (Vanilla, modded, Spigot under `modern`) are warn-skipped, not blocked
- A **Server** has exactly one **Status** at any time, and at most one active **Container**
- A **User** has zero or more **Assignments**; each **Assignment** binds the user to one **Group** at one **Scope**
- A **Network** contains zero or more **Servers** and is used as a **Scope** for **Assignments**
- An **itzg Image Tag** and a **Minecraft Version** are independent fields — changing either sets **Needs Recreate** on the **Server**
- The **Control Stream** is per-**Node**; the **Bulk Data Service** opens a new connection per file transfer

## Example dialogue

> **Dev:** "When an operator changes the **Minecraft Version** on a stopped **Server**, what happens?"

> **Domain expert:** "Master sets **Needs Recreate** on the **Server** record. Nothing is sent to the **Agent** yet."

> **Dev:** "So the **Container** isn't touched until start?"

> **Domain expert:** "Exactly. When the operator calls start, master checks **Needs Recreate** — if true, it sends `pullImage`, then `removeContainer` (if a **Container** already exists), then `createContainer` with the updated `VERSION` env var. The flag clears after the new container is up."

> **Dev:** "What if the **Node**'s **Agent** is disconnected when start is called?"

> **Domain expert:** "Master returns `502 Bad Gateway`. The **Server Status** stays `STOPPED`. The operator retries once the **Agent** reconnects and the **Node** is back to `ACTIVE`."

> **Dev:** "And the **itzg Image Tag** — does changing that work the same way?"

> **Domain expert:** "Yes. It's just another property that requires recreation. **itzg Image Tag** controls which tooling version wraps the server; **Minecraft Version** controls the game version
> inside that tooling. They're independent — you can change either or both, and one **Needs Recreate** flag covers both."

## Frontend & UI

| Term                 | Definition                                                                                                                   | Aliases to avoid               |
|----------------------|------------------------------------------------------------------------------------------------------------------------------|--------------------------------|
| **Dashboard**        | The home page of the app, showing server stat cards, node health table, and recent activity                                  | Home, landing page             |
| **Shell**            | The main app layout wrapper consisting of a top bar, collapsible sidebar, and content area                                   | Layout, frame, app shell       |
| **Sidebar**          | The left navigation panel within the Shell, listing servers, networks, nodes, and system pages                               | Nav, menu, drawer              |
| **Stat Card**        | A metric display card on the Dashboard showing a label, value, and optional sub-text, with an accent colour for error states | Metric card, widget, tile      |
| **PageHeader**       | The title bar at the top of each content page, with a title, optional subtitle, and optional action button                   | Page title, heading bar        |
| **Node Metric**      | CPU, memory, and disk usage data reported by the agent at regular intervals                                                  | Resource usage, telemetry      |
| **Container Metric** | Per-container CPU and memory usage sampled by the agent                                                                      | Container stats, resource data |

## Testing

| Term            | Definition                                                                                                                                 | Aliases to avoid                         |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------|
| **Unit Test**   | A Vitest + React Testing Library test that exercises a single component, hook, or pure function in isolation with jsdom                    | Component test, frontend test            |
| **System Test** | A Kotest + Testcontainers test that boots real PostgreSQL, master, and agent Docker containers and exercises the REST API and gRPC streams | Integration test, e2e test, backend test |
| **E2E Test**    | A browser-based test (not yet implemented) that runs the full Next.js frontend against a running backend in a headless browser             | Frontend e2e, integration test           |

## Relationships

- The **Dashboard** is the default page within the **Shell**
- The **Sidebar** lives inside the **Shell** and provides navigation to all pages
- A **Stat Card** is a visual element that can appear on the **Dashboard** or on a **Server** detail page

## Example dialogue

> **Dev:** "When an operator changes the **Minecraft Version** on a stopped **Server**, what happens?"

> **Domain expert:** "Master sets **Needs Recreate** on the **Server** record. Nothing is sent to the **Agent** yet."

> **Dev:** "So the **Container** isn't touched until start?"

> **Domain expert:** "Exactly. When the operator calls start, master checks **Needs Recreate** — if true, it sends `pullImage`, then `removeContainer` (if a **Container** already exists), then
`createContainer` with the updated `VERSION` env var. The flag clears after the new container is up."

> **Dev:** "What if the **Node**'s **Agent** is disconnected when start is called?"

> **Domain expert:** "Master returns `502 Bad Gateway`. The **Server Status** stays `STOPPED`. The operator retries once the **Agent** reconnects and the **Node** is back to `ACTIVE`."

> **Dev:** "And the **itzg Image Tag** — does changing that work the same way?"

> **Domain expert:** "Yes. It's just another property that requires recreation. **itzg Image Tag** controls which tooling version wraps the server; **Minecraft Version** controls the game version inside that tooling. They're independent — you can change either or both, and one **Needs Recreate** flag covers both."

## Flagged ambiguities

- **"server"** is overloaded: it means the CraftPanel-managed game **Server** entity, the master backend process, and loosely "the machine". Prefer **Server** (capitalised) for the game entity, **Master** for the backend, and **Node** for the machine.
- **"version"** was historically conflated with **itzg Image Tag** in code and tests. The `itzgImageTag` field is the Docker image tag (e.g. `"2024-11-01"`); `mcVersion` is the Minecraft game version (e.g. `"1.21.4"`). Never use "version" unqualified when both meanings are in scope.
- **"image"** can mean the itzg Docker image or the full `image:tag` string. Prefer **itzg Image Tag** when discussing the tag specifically; use **Docker image** when discussing the full image reference passed to `createContainer`.
