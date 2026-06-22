# gRPC Schema

All communication between master and agent uses gRPC over TLS. The REST API is exclusively for browser↔master — no REST endpoints are exposed to agents.

The full proto definition is at `proto/craftpanel.proto` (repo root).

## Connection model

The agent always initiates connections to master. Master never dials out to agents. Two connection types are used.

### Control connection (persistent)

One long-lived bidirectional streaming RPC per node, established by the agent on startup and re-established automatically on disconnect. All always-on traffic flows through this connection.

**Master → Agent (commands):**

- Container lifecycle: create, start, stop, restart, remove
- Backup: trigger backup, delete backup file
- Migration: prepare rsync receive endpoint, start rsync
- Node lifecycle: shutdown

**Agent → Master (observability):**

- Node state snapshot — sent as the first message on every (re)connect
- Node metrics (CPU, RAM, disk, net) — every 60 seconds. Network I/O is summed across physical interfaces only (`/proc/net/dev`); loopback (`lo`) and Docker-managed virtual interfaces (`docker0`,
  `br-*`, `veth*`) are excluded.
- Container metrics (per server, CPU, RAM, net, block I/O) — every 60 seconds. Block I/O (`blkio_stats` from Docker Stats) are cumulative read/write byte totals since container start; counters reset
  when the container restarts, applying the same reset-detection handling as network I/O.
- Server health and status updates
- Player count and player list updates
- Rsync progress and completion during migration
- Backup progress and completion
- Shutdown acknowledgement

**Command acknowledgement:**

Commands are fire-and-forget at the application layer. gRPC transport-level delivery is sufficient confirmation the agent received the command. Outcomes surface through the observability stream — a
failed container start appears as `UNHEALTHY` in a `ServerStatusUpdate`, not as a command error.

### Data operations (multiplexed over control stream)

Console sessions and small file operations (list, read, write, delete, move, copy, make directory) are multiplexed over the existing control stream using a `request_id` correlation field. Each request
carries a UUID; the agent echoes it in the response so master can route the reply back to the waiting caller.

### Bulk transfers (BulkDataService — agent-initiated)

Large file transfers (upload and download) use a dedicated `BulkDataService` hosted on master. The agent dials master — preserving the agent-always-initiates invariant — on an on-demand basis when
commanded via the control stream. This isolates multi-GB transfers from the control stream and prevents head-of-line blocking on metrics and console I/O.

**Operations:**

- `StreamToMaster` — agent streams file data up to master (user file/backup download)
- `ReceiveFromMaster` — master streams upload data down to agent (user file upload)

---

## Bootstrap and registration

### First connect (no stored node key)

1. Agent starts with no stored node key
2. Agent calls `RegisterNode` with the bootstrap token and its metadata (hostname, IPs, RAM, CPU, agent version)
3. Master validates the bootstrap token against config
4. Master creates a node record with status `PENDING` and generates a unique 256-bit node key
5. Master returns the node key and node UUID
6. Agent persists the node key to disk (e.g. `/etc/craftpanel/node.key`)
7. Agent opens the `Control` stream and sends a `NodeStateSnapshot` as its first message
8. Master will not dispatch commands until an admin trusts the node via `POST /nodes/{id}/trust`

### Subsequent connects (node key present)

1. Agent calls `IdentifyNode` with the stored node key and current metadata
2. If `ACTIVE` — master accepts; agent opens the `Control` stream and sends `NodeStateSnapshot`
3. If `PENDING` — master refuses with `PERMISSION_DENIED`; agent must wait for admin approval before reconnecting
4. If `REJECTED` or `DECOMMISSIONED` — master refuses; agent logs the error and halts

### Bootstrap token

A single shared secret in the master config (`node_bootstrap_token`). Reusable — any number of agents can register with it. A leaked token can only produce harmless `PENDING` records. Rotate in config
and restart master if compromised.

### Node key rotation

Admin calls `POST /nodes/{id}/token/rotate`. Master immediately invalidates the key. On next connect the agent receives `REJECTED` and halts. Re-provision by handing the agent a fresh registration (
clear the local key file and restart — agent falls back to `RegisterNode` with the bootstrap token).

### BulkDataService authentication

Bulk transfer connections are authenticated using the node key. On `StreamToMaster` and `ReceiveFromMaster`, the agent presents its raw node key in the first message; master hashes it and verifies
against the stored hash in the database — the same credential used by `IdentifyNode`. No separate per-node token is required.

---

## Node state snapshot

The first `AgentMessage` on every `Control` stream after connect must be a `NodeStateSnapshot`. Master reconciles DB server statuses against what the agent reports before issuing any commands.

`NodeStateSnapshot` includes a `router_running` boolean indicating whether the agent's mc-router container is up. Master uses this to derive the node's initial `health`:

- `router_running = true` → `health = HEALTHY`
- `router_running = false` → `health = DEGRADED`

The same field is included in every subsequent `NodeMetricsUpdate`. Master emits a `node.status` WebSocket event only when the derived health value changes between polls.

Container run states:

| State     | Meaning                                                                                                                                                                   |
|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `RUNNING` | Container is running normally                                                                                                                                             |
| `STOPPED` | Container was stopped cleanly via CraftPanel                                                                                                                              |
| `EXITED`  | Container exited unexpectedly — master-level restart did not recover it (e.g. restart limit hit, or manually stopped outside CraftPanel). Surfaced distinctly in the UI. |

---

## Container lifecycle

Game server and rsync containers are created with `restart_policy = no` — the master owns restart-on-crash decisions. The agent reports container state via `NodeStateSnapshot` on reconnect, and the master reissues start commands if needed. Only the mc-router container uses `restart_policy = unless-stopped`.

### Graceful stop

Rather than RCON, CraftPanel uses container stdin for graceful shutdown. The `stop_command` field on `StopContainerCommand` and `RestartContainerCommand` carries the command string to write to stdin
before Docker stop.

Default stop commands by server type (configurable per server in the UI):

| Server type                                                                            | Default stop command |
|----------------------------------------------------------------------------------------|----------------------|
| `VANILLA`, `PAPER`, `FABRIC`, `FOLIA`, `FORGE`, `NEOFORGE`, `QUILT`, `SPIGOT`, `LIMBO` | `stop`               |
| `VELOCITY`, `BUNGEECORD`, `WATERFALL`                                                  | `end`                |

Stop sequence:

1. Write `stop_command` + newline to container stdin via Docker attach
2. Wait up to `timeout_seconds` for the container to exit
3. If still running — Docker stop (SIGTERM → SIGKILL)
4. Report final state via `ServerStatusUpdate`

Restart follows the same stop sequence then starts the container again. Docker restart is not used.

---

## Migration and rsync

Server data is transferred between nodes using an `alpine:latest` Docker container that installs `rsync` at runtime via `apk`. The same container serves both roles (sender and receiver) depending on
the command passed at runtime. The `rsync_image` field on `PrepareRsyncReceiveCommand` and `StartRsyncCommand` overrides the image when set — point it at a pre-built image to skip the runtime install.

### Migration flow

1. Master sends `PrepareRsyncReceiveCommand` to destination agent
2. Destination agent runs `craftpanel-rsync` in rsyncd mode on the pre-allocated port, generates a one-time password, mounts the destination data path
3. Destination agent sends `RsyncReadyUpdate` to master with the one-time password
4. Master sends `StartRsyncCommand` to source agent with destination IP, port, and password
5. Source agent runs `craftpanel-rsync` in rsync client mode, mounting the source data path
6. Source agent streams `RsyncProgressUpdate` messages to master on the control stream
7. On completion source agent sends `RsyncCompleteUpdate`; destination agent stops and removes the rsyncd container and frees the port
8. Steps 1–7 repeat for the final delta pass (`is_final_pass = true`) after the server is stopped

Server status during migration is derived from the active `migrations` record — no separate flag on the server. See [Migrations data model](../data-model/migrations.md).

!!! note "Future consideration: distributed filesystem"
The rsync approach keeps nodes fully independent with local disk I/O — optimal for Minecraft world saves. If migration frequency or data volume increases significantly, a distributed filesystem would
eliminate rsync entirely by decoupling data from the node running the container. Candidates to evaluate: **SeaweedFS** (lightweight, S3-compatible, volume servers could run on nodes themselves), *
*GlusterFS** (simpler than Ceph, FUSE mount), **Ceph** (full-featured but operationally heavy — likely overkill). Local disk + rsync is the right starting point.

---

## Node shutdown

### Planned shutdown

1. Admin initiates from UI → master sends `ShutdownCommand` on the control stream
2. Agent sends stdin stop command to each running container, waits up to `timeout_seconds`
3. Force-stops any containers that did not exit cleanly
4. Sends `ShutdownAcknowledgeUpdate` with graceful and forced counts
5. Agent process exits; stream drops naturally

Master on receiving `ShutdownAcknowledgeUpdate`:

- Marks all servers on the node `STOPPED`
- Marks node `health = UNREACHABLE` (lifecycle `status` remains `ACTIVE`)

### Unplanned shutdown / agent crash

Master detects liveness loss when the `Control` stream drops or when no `NodeMetricsUpdate` is received within 2 consecutive intervals (2 minutes). On detection:

- Node `health` marked `UNREACHABLE`
- All servers on the node marked `UNHEALTHY`
- Any in-progress migrations involving the node marked `FAILED`
- Any in-progress backups on the node marked `FAILED`
- Alert events fired for configured thresholds

On agent reconnect master waits for the `NodeStateSnapshot` before issuing commands, reconciling actual container states — Docker may have restarted containers that master marked unhealthy.

---

## Gradle setup

Proto is the source of truth. Kotlin code is generated from the proto definition — never written by hand. See the `craftpanel.protobuf-convention` Gradle convention plugin in [`buildSrc/`](https://github.com/nelsongraca/craftpanel/tree/main/buildSrc) and the version catalog at `gradle/libs.versions.toml` for the current dependency versions.

**Project structure:**

```
craftpanel/
├── proto/
│   └── craftpanel.proto    ← single flat file, source of truth
├── master/
│   └── src/main/kotlin/...     ← consumes generated stubs
└── agent/
    └── src/main/kotlin/...     ← consumes generated stubs
```

The `proto/` directory sits at the monorepo root, shared between master and agent modules. Generated code lands in `build/generated/source/proto/` and is treated as a source set automatically by the
Protobuf Gradle plugin.
