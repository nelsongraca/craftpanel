# Node Management

A **node** is any machine running the CraftPanel Agent alongside Docker. The master backend orchestrates all nodes and is the sole source of truth for their state.

## Registration

Node registration is fully agent-initiated over gRPC. There is no UI step to create a node beforehand — the agent registers itself on first startup using a shared bootstrap token configured in master.

1. The operator sets `node_bootstrap_token` in the master config file — a single shared secret used by all agents to register
2. The agent is started with the master gRPC address and the bootstrap token as environment variables (or config file)
3. On first startup the agent calls `RegisterNode` over gRPC, providing the bootstrap token and its metadata (hostname, IPs, total RAM and CPU)
4. Master validates the bootstrap token, creates a node record with status `PENDING`, generates a unique 256-bit node key, and returns it to the agent
5. The agent persists the node key to its local config file (mounted on the host) and uses it for all subsequent connections
6. The node remains `PENDING` — master will not dispatch any commands to it until an admin trusts it from the UI
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

Each node has a configured resource envelope (total allocatable RAM, CPU shares). Master tracks allocated vs. available capacity and prevents over-provisioning when creating or resizing servers.

## Agent Configuration

The agent is configured entirely through environment variables.

| Variable              | Default                   | Description                                                                                     |
|-----------------------|---------------------------|-------------------------------------------------------------------------------------------------|
| `MASTER_HOST`         | `localhost`               | Hostname or IP of the master gRPC server                                                        |
| `MASTER_GRPC_PORT`    | `50051`                   | Port of the master gRPC server                                                                  |
| `GRPC_TLS_CERT`       | *(empty — TLS disabled)*  | Path to the TLS CA certificate used to verify the master. Leave empty to disable TLS.           |
| `NODE_BOOTSTRAP_TOKEN`| `changeme`                | Shared secret used for first-time node registration. Not needed after the node key is persisted |
| `GRPC_CA_CERT_FILE`   | `/etc/craftpanel/grpc-ca.crt` | Path to the master's CA certificate PEM. Must be pre-provisioned — copy from master's cert store and mount read-only into the agent container. |
| `NODE_KEY_FILE`       | `/etc/craftpanel/node.key`| Path where the agent persists its node key after registration. Mount a writable volume so it survives restarts. |
| `DOCKER_SOCKET`       | `unix:///var/run/docker.sock` | Docker socket path                                                                          |
| `AGENT_VERSION`       | `dev`                     | Version string reported to master during registration                                           |
| `APP_PROFILE`         | `prod`                    | Runtime profile. Set to `dev` to allow plaintext gRPC (development only) |
| `DATA_PATH`           | `/data`                   | Base directory on the node host where server data volumes are stored                            |
| `PUBLIC_IP_URL`       | *(empty)*                 | URL to fetch the node's public IP address (e.g. `https://api.ipify.org`). When empty, the private IP is reported instead |
| `MCROUTER_IMAGE`      | `itzg/mc-router:latest`   | Docker image used when provisioning the mc-router container on startup                          |
| `MCROUTER_UPDATE_ON_START` | `true`             | Pull the mc-router image on every agent startup to keep it up to date. Set to `false` to skip the pull and use whatever image is already cached locally |

### mc-router provisioning

On startup the agent automatically provisions a single `craftpanel-mc-router` container on the local Docker daemon. This container routes incoming Minecraft TCP connections to the correct game server container using Docker label-based hostname matching (label `mc-router.hostname=<hostname>`).

When `MCROUTER_UPDATE_ON_START=true` (default) the agent pulls the configured image before creating the container, so the node always runs the latest version of mc-router. Set to `false` in environments where image pulls are restricted or where a pinned digest is baked into `MCROUTER_IMAGE`.

If the mc-router container is already running, the pull (if enabled) still executes so the local image cache is updated, but the running container is not restarted — the update takes effect on the next agent restart.

## Colocation with Master

The master backend, PostgreSQL, and frontend may share hardware with a node agent. In this configuration the master node also hosts server containers alongside the management stack. Resource allocated
to server containers is tracked and subtracted from the node's available capacity the same as any other node.
