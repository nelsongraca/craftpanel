# gRPC Schema

All communication between master and agent uses gRPC over mTLS. The REST API is exclusively for browser↔master — no REST endpoints are exposed to agents.

The full proto definition is at [`craftpanel.proto`](craftpanel.proto).

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
- Node metrics (CPU, RAM, disk, net) — every 60 seconds
- Container metrics (per server, CPU, RAM, net) — every 60 seconds
- Server health and status updates
- Player count and player list updates
- Rsync progress and completion during migration
- Backup progress and completion
- Shutdown acknowledgement

**Command acknowledgement:**

Commands are fire-and-forget at the application layer. gRPC transport-level delivery is sufficient confirmation the agent received the command. Outcomes surface through the observability stream — a failed container start appears as `UNHEALTHY` in a `ServerStatusUpdate`, not as a command error.

### Data connection (on-demand)

A separate connection per operation for large or interactive data. Torn down when the operation completes. Failure isolation — a stalled file download or console session cannot affect node liveness detection or metric streaming.

**Operations:**

- Console session — bidirectional stream proxying browser terminal input to container stdin and container stdout/stderr back to the browser via WebSocket
- File operations: list, read, write, delete, move, copy, make directory
- File upload — client-streaming RPC
- File download — server-streaming RPC

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
3. If `PENDING` — master accepts; agent opens the `Control` stream but master holds commands until trusted
4. If `REJECTED` — master refuses; agent logs the error and halts

### Bootstrap token

A single shared secret in the master config (`node_bootstrap_token`). Reusable — any number of agents can register with it. A leaked token can only produce harmless `PENDING` records. Rotate in config and restart master if compromised.

### Node key rotation

Admin calls `POST /nodes/{id}/token/rotate`. Master immediately invalidates the key. On next connect the agent receives `REJECTED` and halts. Re-provision by handing the agent a fresh registration (clear the local key file and restart — agent falls back to `RegisterNode` with the bootstrap token).

---

## Node state snapshot

The first `AgentMessage` on every `Control` stream after connect must be a `NodeStateSnapshot`. Master reconciles DB server statuses against what the agent reports before issuing any commands.

Container run states:

| State | Meaning |
|---|---|
| `RUNNING` | Container is running normally |
| `STOPPED` | Container was stopped cleanly via CraftPanel |
| `EXITED` | Container exited unexpectedly — Docker restart policy did not recover it (e.g. restart limit hit, or manually stopped outside CraftPanel). Surfaced distinctly in the UI. |

---

## Container lifecycle

All containers are created with `restart_policy = unless-stopped`. Docker restarts containers automatically after agent restarts or node reboots — the agent does not need to restart containers on reconnect, only report their current state via `NodeStateSnapshot`.

### Graceful stop

Rather than RCON, CraftPanel uses container stdin for graceful shutdown. The `stop_command` field on `StopContainerCommand` and `RestartContainerCommand` carries the command string to write to stdin before Docker stop.

Default stop commands by server type (configurable per server in the UI):

| Server type | Default stop command |
|---|---|
| `VANILLA`, `PAPER`, `FABRIC`, `FOLIA`, `FORGE`, `NEOFORGE`, `QUILT`, `SPIGOT`, `LIMBO` | `stop` |
| `VELOCITY`, `BUNGEECORD`, `WATERFALL` | `end` |

Stop sequence:

1. Write `stop_command` + newline to container stdin via Docker attach
2. Wait up to `timeout_seconds` for the container to exit
3. If still running — Docker stop (SIGTERM → SIGKILL)
4. Report final state via `ServerStatusUpdate`

Restart follows the same stop sequence then starts the container again. Docker restart is not used.

---

## Migration and rsync

Server data is transferred between nodes using a purpose-built `craftpanel-rsync` Docker container — an Alpine-based image containing only `rsync` and `rsyncd`. The same image serves both roles (sender and receiver) depending on the command passed at runtime.

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
    The rsync approach keeps nodes fully independent with local disk I/O — optimal for Minecraft world saves. If migration frequency or data volume increases significantly, a distributed filesystem would eliminate rsync entirely by decoupling data from the node running the container. Candidates to evaluate: **SeaweedFS** (lightweight, S3-compatible, volume servers could run on nodes themselves), **GlusterFS** (simpler than Ceph, FUSE mount), **Ceph** (full-featured but operationally heavy — likely overkill). Local disk + rsync is the right starting point.

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
- Marks node `DEGRADED`

### Unplanned shutdown / agent crash

Master detects liveness loss when the `Control` stream drops or when no `NodeMetricsUpdate` is received within 2 consecutive intervals (2 minutes). On detection:

- Node marked `DEGRADED`
- All servers on the node marked `UNHEALTHY`
- Any in-progress migrations involving the node marked `FAILED`
- Any in-progress backups on the node marked `FAILED`
- Alert events fired for configured thresholds

On agent reconnect master waits for the `NodeStateSnapshot` before issuing commands, reconciling actual container states — Docker may have restarted containers that master marked unhealthy.

---

## Gradle setup

Proto is the source of truth. Kotlin code is generated from the proto definition — never written by hand.

```kotlin
// build.gradle.kts
plugins {
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("com.google.protobuf:protobuf-kotlin:3.26.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.26.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
                create("grpckt")
            }
            it.builtins {
                create("kotlin")
            }
        }
    }
}
```

**Project structure:**

```
craftpanel/
├── proto/
│   └── craftpanel/agent/v1/
│       └── craftpanel.proto    ← source of truth
├── master/
│   └── src/main/kotlin/...     ← consumes generated stubs
└── agent/
    └── src/main/kotlin/...     ← consumes generated stubs
```

The `proto/` directory sits at the monorepo root, shared between master and agent modules. Generated code lands in `build/generated/source/proto/` and is treated as a source set automatically by the Protobuf Gradle plugin.
