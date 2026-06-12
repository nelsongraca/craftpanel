# Node Management

A **node** is any machine running the CraftPanel Agent alongside Docker. The master backend orchestrates all nodes and is the sole source of truth for their state.

## Registration

Node registration is fully agent-initiated over gRPC. There is no UI step to create a node beforehand — the agent registers itself on first startup using a shared bootstrap token configured in master.

1. The operator sets `node_bootstrap_token` in the master config file — a single shared secret used by all agents to register
2. The agent is started with the master gRPC address and the bootstrap token as environment variables (or config file)
3. On first startup the agent calls `RegisterNode` over gRPC, providing the bootstrap token and its metadata (hostname, IPs, total RAM and CPU)
4. Master validates the bootstrap token, creates a node record with status `PENDING`, generates a unique 256-bit node key, and returns it to the agent
5. The agent persists the node key to its local config file (mounted on the host) and uses it for all subsequent connections
6. The node remains `PENDING` — master will refuse its control stream connection until an admin trusts it
7. An admin reviews the new node in the UI and clicks **Trust** — the node moves to `ACTIVE` and is ready for use

On subsequent agent restarts the agent reads its stored node key and calls `IdentifyNode` instead of `RegisterNode`. No bootstrap token is needed after first registration.

!!! note "Security"
A leaked bootstrap token can only produce `PENDING` node records. These are harmless — they have no effect on the system until an admin explicitly trusts them. Rotate the bootstrap token in master
config and restart master if it is compromised.

## Key Rotation

A node key can be rotated from the UI at any time. Master immediately invalidates the old key — the agent will be rejected on its next connection and must re-register using the bootstrap token (clear
the local key file and restart the agent).

## Metrics Collection

The agent collects and streams the following to master continuously, reading from the Linux `/proc` filesystem. No additional monitoring software is required on nodes.

| Metric                                     | Source                    |
|--------------------------------------------|---------------------------|
| CPU utilisation (per-core and aggregate)   | `/proc/stat`              |
| RAM usage (total, used, available)         | `/proc/meminfo`           |
| Network I/O (bytes in/out per interface)   | `/proc/net/dev`           |
| Disk usage (total, used, free)             | `/proc/mounts` + `statfs` |
| Per-container CPU, RAM, network, block I/O | Docker Stats API          |

Master stores metric snapshots at **1-minute intervals** in PostgreSQL. Historical data is retained for a configurable period (default 30 days).

## Capacity Tracking

Each node has a configured resource envelope (total allocatable RAM, CPU shares). Before reporting capacity to master, the agent subtracts `SYSTEM_RESERVED_RAM_MB` so that OS and infrastructure daemons always retain guaranteed headroom. Master tracks allocated vs. available capacity and prevents over-provisioning when creating or resizing servers.

## Agent Configuration

The agent is configured entirely through environment variables.

| Variable                     | Default                       | Description                                                                                                                                                                                                                                  |
|------------------------------|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MASTER_HOST`                | `localhost`                   | Hostname or IP of the master gRPC server                                                                                                                                                                                                     |
| `MASTER_GRPC_PORT`           | `50051`                       | Port of the master gRPC server                                                                                                                                                                                                               |
| `MASTER_HTTP_PORT`           | `8080`                        | Port of the master HTTP server. Used only during private IP auto-discovery (see below).                                                                                                                                                      |
| `APP_PROFILE`                | `prod`                        | Runtime profile. Set to `dev` to allow plaintext gRPC (development only).                                                                                                                                                                   |
| `NODE_BOOTSTRAP_TOKEN`       | `changeme`                    | Shared secret used for first-time node registration. Not needed after the node key is persisted.                                                                                                                                             |
| `GRPC_TLS_CERT`              | *(empty — TLS disabled)*      | Path to the TLS CA certificate used to verify the master. Leave empty to disable TLS.                                                                                                                                                       |
| `GRPC_CA_CERT_FILE`          | `/app/config/grpc-ca.crt`     | Path where the agent caches the CA cert received from master during registration. The agent writes this file on first connect; mount a writable directory so it persists across restarts.                                                    |
| `NODE_KEY_FILE`              | `/app/config/node.key`        | Path where the agent persists its node key after registration. Mount a writable volume so it survives restarts.                                                                                                                              |
| `NODE_HOSTNAME`              | *(auto-detected)*             | Hostname reported to master. Overrides `InetAddress.getLocalHost().hostName`. Useful in containerised environments where the auto-detected name is an ephemeral container ID.                                                               |
| `NODE_PRIVATE_IP`            | *(auto-discovered)*           | Private IP reported to master. When not set the agent auto-discovers it (see **Private IP discovery** below). Override when auto-discovery returns the wrong address (e.g. multiple NICs, VPN interfaces, or unusual network topologies).    |
| `PUBLIC_IP_URL`              | *(empty)*                     | URL to fetch the node's public IP (e.g. `https://api.ipify.org`). When empty, the private IP is used as the public IP.                                                                                                                      |
| `DOCKER_SOCKET`              | `unix:///var/run/docker.sock` | Docker socket path.                                                                                                                                                                                                                          |
| `AGENT_VERSION`              | `dev`                         | Version string reported to master during registration.                                                                                                                                                                                       |
| `DATA_PATH`                  | `/data`                       | Container-internal path the agent uses for file access (file browser, backups, migrations).                                                                                                                                                 |
| `HOST_DATA_PATH`             | *(value of `DATA_PATH`)*      | Host path Docker uses as the bind-mount source when creating server containers. Must match the node's **Data Path** field in the UI. Defaults to `DATA_PATH`.                                                                               |
| `CRAFTPANEL_NETWORK`         | `craftpanel`                  | Name of the Docker bridge network shared by the agent, mc-router, and all server containers. The network must exist before the agent starts. See [Docker Network](../networking/index.md#docker-network).                                   |
| `CRAFTPANEL_CONTAINER_PREFIX`| `craftpanel`                  | Prefix applied to all container names created by this agent (e.g. `craftpanel-<server-id>`). Change only when running multiple isolated CraftPanel stacks on the same Docker daemon.                                                        |
| `MCROUTER_IMAGE`             | `itzg/mc-router:latest`       | Docker image used when provisioning the mc-router container on startup.                                                                                                                                                                      |
| `MCROUTER_UPDATE_ON_START`   | `true`                        | Pull the mc-router image on every agent startup. Set to `false` to skip the pull and use the locally cached image.                                                                                                                           |
| `SYSTEM_RESERVED_RAM_MB`     | `0`                           | Megabytes of RAM the agent will not offer to servers. Subtracted from the node's physical total before reporting to master. On a co-located node running master + PostgreSQL, `1024`–`2048` is typical.                                     |
| `METRICS_POLL_INTERVAL_SECONDS` | `60`                       | How often the agent polls `/proc` and Docker Stats for node and container metrics. Minimum 5 seconds.                                                                                                                                        |

### Private IP discovery

The agent reports its private IP to master so server containers on different nodes can reach each other (e.g. during migration via rsync). When `NODE_PRIVATE_IP` is not set, the agent resolves it automatically using the following fallback chain:

1. **Ask master** — `GET http://<MASTER_HOST>:<MASTER_HTTP_PORT>/api/nodes/my-ip`. Master returns the source IP of the incoming HTTP request. This works correctly in most Docker and VM setups where the agent's outbound IP is its private IP on the relevant network.
2. **Local hostname lookup** — `InetAddress.getLocalHost().hostAddress`. Least reliable; may return `127.0.0.1` or a non-routable address on some systems.

Set `NODE_PRIVATE_IP` explicitly when auto-discovery returns the wrong address — for example, on hosts with multiple NICs, VPN tunnels, or Kubernetes pod networks where the outbound IP seen by master differs from the IP other agents should use to reach this node.

### Data path alignment

The agent uses two separate path env vars because the agent runs inside a container but must also tell the Docker daemon (on the host) where to bind-mount server data:

| Env var | Purpose |
|---|---|
| `DATA_PATH` | Path **inside the agent container** — used for file browser, backups, migrations |
| `HOST_DATA_PATH` | Path **on the host** — passed to Docker as bind-mount source when creating server containers. Defaults to `DATA_PATH`. |

Server data lives at `{HOST_DATA_PATH}/servers/{server-id}` on the host. When master creates a Minecraft container it bind-mounts this exact path into the Minecraft container at `/data`. The agent reads and writes files via `DATA_PATH`.

In a standard setup where the same absolute path is used on both the host and inside the agent container, set both to the same value (or just set `DATA_PATH`). When paths differ — e.g. host has `/mnt/data` mounted at `/srv/craftpanel` inside the agent — set `DATA_PATH=/srv/craftpanel` and `HOST_DATA_PATH=/mnt/data`.

Three values must stay in sync:

| Where | Value |
|---|---|
| `HOST_DATA_PATH` env var | Host path for Docker bind-mounts |
| Node record in CraftPanel UI | **Data Path** field (editable after registration) |
| Host filesystem | The directory must exist before the agent starts |

The data directory must be a **bind-mount** in the agent's Docker compose, not a named volume — Docker containers created by the agent reference the raw host path, so it must be reachable from the host Docker daemon at the same path:

```yaml
volumes:
  - /srv/craftpanel:/srv/craftpanel   # same path on both sides
```

The directory must exist before the agent starts; the agent does not create it.

### mc-router provisioning

On startup the agent automatically provisions a single `craftpanel-mc-router` container on the local Docker daemon. This container routes incoming Minecraft TCP connections to the correct game server container using Docker label-based hostname matching (label `mc-router.hostname=<hostname>`).

The mc-router container is attached to the `craftpanel` network (controlled by `CRAFTPANEL_NETWORK`) so it can reach game server containers by their container name. See [Docker Network](../networking/index.md#docker-network).

When `MCROUTER_UPDATE_ON_START=true` (default) the agent pulls the configured image before creating the container, so the node always runs the latest version of mc-router. Set to `false` in environments where image pulls are restricted or where a pinned digest is baked into `MCROUTER_IMAGE`.

If the mc-router container is already running, the pull (if enabled) still executes so the local image cache is updated, but the running container is not restarted — the update takes effect on the next agent restart.

## Colocation with Master

The master backend, PostgreSQL, and frontend may share hardware with a node agent. In this configuration the master node also hosts server containers alongside the management stack. Resource allocated
to server containers is tracked and subtracted from the node's available capacity the same as any other node.
