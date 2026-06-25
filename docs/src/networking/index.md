# Networking & Player Ingress

## Two-Network Model

Each node operates two categories of Docker networks:

| Network | Name | Created by | Purpose |
|---|---|---|---|
| Infra network | `craftpanel` | Operator (pre-created) | Connects agent, mc-router, and all server containers |
| Server Network bridge | `craftpanel-net-<uuid>` | Agent (bridge) / Master (overlay) | Per-Server-Network isolation; containers in the same network reach each other by hostname |
| Standalone server | `craftpanel-server-<uuid>` | Agent (bridge) | Isolates standalone servers (no assigned network) |

### Who creates what

| Actor | Creates |
|---|---|
| Operator | `craftpanel` infra network — must exist before the agent starts |
| Master | `craftpanel-net-<uuid>` overlay networks when `DOCKER_ENDPOINT` is configured (Swarm mode) |
| Agent | `craftpanel-net-<uuid>` bridge networks on the local node; `craftpanel-server-<uuid>` bridges for standalone servers |

## mc-router

Each node runs one instance of [`itzg/mc-router`](https://github.com/itzg/mc-router). It listens on port **25565** and routes incoming Minecraft connections by hostname, reading its routing table from
**Docker container labels**. Master sets the appropriate labels when creating or updating containers — no direct API communication between master and mc-router is required.

The mc-router container is named **`craftpanel-mc-router-<node-id>`** — the node ID suffix ensures uniqueness in Swarm deployments where multiple agents share a Docker context.

Game traffic flows directly from players to mc-router to containers. It never passes through the master backend.

mc-router is attached to the `craftpanel` infra network **and** to every server network bridge/overlay on the node. This allows it to reach game containers across all networks while players only need to connect on port 25565.

### Lifecycle management

The **agent** provisions and manages the mc-router container automatically — no manual setup is required on the node. On every startup the agent:

1. Pulls the configured mc-router image (controlled by `MCROUTER_IMAGE`, default `itzg/mc-router:latest`)
2. Starts the container if it is not already running, or leaves it in place if it is
3. Attaches mc-router to any server network bridges that exist locally

The pull step runs by default so nodes always run the latest mc-router release. It can be disabled by setting `MCROUTER_UPDATE_ON_START=false`, which causes the agent to use whatever image is already
cached locally — useful when the image is pinned to a specific digest or when image pulls are restricted. See [Agent Configuration](../nodes/index.md#agent-configuration) for the full list of env
vars.

## Docker Networks

### The `craftpanel` infra network

The operator must create this network before starting the agent:

```bash
docker network create craftpanel
```

The network name defaults to `craftpanel` and is configurable via the `CRAFTPANEL_NETWORK` env var on the agent. The agent verifies the network exists on startup and **fails fast** if it cannot be
found — it will not start with a missing network.

The agent container must be explicitly attached to this network in the Compose file:

```yaml
services:
  craftpanel-agent:
    # ...
    networks:
      - craftpanel

networks:
  craftpanel:
    external: true
```

The agent attaches every game server container to this network at creation time in addition to any server network or standalone bridge the container belongs to.

### Server Network bridges (`craftpanel-net-<uuid>`)

When master assigns a server to a Server Network, it derives the Docker network name from the Server Network UUID and sets it in `StartContainerCommand.docker_network`. The agent does not re-derive this name — it uses the value sent by master directly.

**Agent network lifecycle:**

1. On `StartContainerCommand`: if the bridge named `craftpanel-net-<uuid>` does not exist, the agent creates it
2. Agent starts the container and attaches it to the bridge
3. Agent attaches mc-router to the bridge (if not already attached)
4. On container removal: if no other containers remain on the bridge, the agent detaches mc-router and deletes the bridge

### Standalone server bridges (`craftpanel-server-<uuid>`)

Servers with no assigned Server Network are placed on an isolated bridge named `craftpanel-server-<uuid>`. The agent creates this bridge when the container starts and deletes it when the server is removed.

### Security note

All containers on the `craftpanel` infra network can reach each other directly by container name. This is an inherent property of a shared Docker bridge network. On a multi-tenant node hosting servers for different trust levels, this is a known limitation. Server Network bridges provide container-level isolation between networks, but containers sharing the infra network can still communicate. Network-policy-level isolation between containers on a shared bridge is not available in plain Docker without external tooling. This is accepted for the current architecture and noted for future improvement.

## Deployment Topologies

### Single-node

All servers run on one node. The agent creates bridge networks locally. No Swarm required. Server Networks and standalone servers are fully supported.

```
Node 1
├── craftpanel (infra bridge)
│   ├── craftpanel-agent
│   └── craftpanel-mc-router-<node-id>
├── craftpanel-net-<uuid> (bridge)
│   ├── velocity-proxy
│   └── survival-server
└── craftpanel-server-<uuid> (bridge, standalone)
    └── creative-server
```

### Multi-node with Swarm

Master is configured with `DOCKER_ENDPOINT` pointing at a Swarm manager. Master creates `craftpanel-net-<uuid>` as an **overlay** network so containers on different nodes can communicate via Docker Swarm networking. Cross-node Server Networks are supported.

```
Swarm overlay: craftpanel-net-<uuid>
  Node 1: velocity-proxy ──▶ (overlay) ──▶ Node 2: survival-server
```

### Multi-node without Swarm

Each agent runs independently. Master does not manage overlay networks. Server Networks whose members are all on the same node work normally using bridges. Cross-node Server Networks are **rejected at the API level with 422** — see [Server Networks](../servers/networks.md#cross-node-constraints).

## DNS Structure

| Record                  | Type | Value            | Managed by                           |
|-------------------------|------|------------------|--------------------------------------|
| `node1.mc.domain.tld`   | A    | Node 1 public IP | Administrator (static)               |
| `node2.mc.domain.tld`   | A    | Node 2 public IP | Administrator (static)               |
| `*.node1.mc.domain.tld` | A    | Node 1 public IP | Administrator (static, wildcard)     |
| `<name>.mc.domain.tld`  | A    | Current node IP  | Master (dynamic, per exposed server) |

## External Exposure

Each server has an **expose externally** toggle. When enabled:

1. The user chooses a subdomain (e.g. `survival`) — master validates it is unique
2. The public hostname becomes `survival.mc.domain.tld`
3. Master creates an A record via the DNS provider API (Cloudflare recommended, TTL 60 seconds) pointing to the current node's IP
4. The mc-router label `mc-router.host=survival.mc.domain.tld` is set on the container
5. mc-router on the node picks up the label and begins routing that hostname to the container
6. The public hostname is shown on the server detail page for users to add to their Minecraft client

### Custom domains (bring your own DNS)

In addition to the managed subdomain, a server can have a **custom hostname** — a user-supplied FQDN such as `play.their-domain.com`. The panel only configures mc-router routing for this hostname; it never manages DNS for custom domains.

**User responsibility:** The user must point an A record (or CNAME to an A record) at the node's public IP themselves using their own DNS provider. The panel cannot create, update, or delete records in user-owned DNS zones.

**Additive routing:** Both the managed subdomain and the custom hostname can be active simultaneously. The mc-router label becomes a comma-joined list, e.g. `survival.mc.domain.tld,play.their-domain.com`. Both addresses work.

**Canonical hostname:** The **canonical (display) hostname** shown on the server detail page (`canonical_hostname` API field) is the custom hostname when set, otherwise the managed subdomain hostname. Setting a custom hostname does not remove the managed subdomain route.

**Setting or clearing a custom hostname triggers a container recreate** — the mc-router label is baked at container creation, so it must be refreshed to pick up the change.

**Validation:** A custom hostname must be a valid RFC-1123 hostname. The panel rejects:

- Hostnames already in use by another server's custom hostname
- Hostnames that match an existing managed DNS record name
- Hostnames under a panel-managed domain suffix (e.g. anything ending in `.mc.domain.tld`) — those must go through the managed subdomain path

**Disabling external exposure** keeps a still-set custom hostname routing through mc-router. Only the managed subdomain half of the label is removed; the custom half remains. The user's traffic still reaches the server via their custom domain even when the panel subdomain is disabled.

!!! warning "Custom domains and migration"
    When a server is migrated to a different node, the panel updates the managed A record to point to the destination node's IP automatically. **Custom domains are not updated.** The user's A/CNAME still points at the old node's IP until they update it manually. Players using the custom hostname will be routed to the old node until the user updates their DNS. This is a user responsibility — the panel cannot touch user-owned DNS zones.

### mc-router auto-discovery labels

mc-router runs with `IN_DOCKER=true` so it subscribes to the Docker event stream and routes by these container labels (set by the agent at container creation):

| Label | Value | Purpose |
|---|---|---|
| `mc-router.host` | the public hostname(s) | routing hostname; comma-separated for multiple hostnames |
| `mc-router.port` | `25565` | container-internal Minecraft port |
| `mc-router.network` | the `craftpanel` network name | which Docker network mc-router dials the backend on |

The label key is `mc-router.host` (not `hostname`) and `IN_DOCKER=true` is required — without it the mounted Docker socket is unused and labels are ignored.

When expose is disabled, no public DNS record exists. The server is reachable only within its Docker network or by node IP + port (used for cross-node proxy easy-mode configuration).

## Internal Hostname

Whether or not a server is externally exposed, each server has an **internal hostname** shown on its detail page. This is the address used when configuring a proxy in easy mode:

- Same node as proxy → Docker container hostname (e.g. `craftpanel-abc123`)
- Different node → `node-private-ip:port`

## DNS Providers

Master uses a pluggable `DnsProvider` interface. The active provider is selected at startup via the `DNS_PROVIDER` environment variable.

| Value            | Behaviour                                                                         |
|------------------|-----------------------------------------------------------------------------------|
| `none` (default) | DNS records are not created. Subdomains are still stored for mc-router label use. |
| `cloudflare`     | A records are managed via the Cloudflare API. Requires `CF_API_TOKEN`.            |

The zone ID and domain suffix are configured per Server Network (not globally), allowing each network to use a different zone.

### DNS Provider Configuration (Cloudflare)

```bash
DNS_PROVIDER=cloudflare
CF_API_TOKEN=<your-cloudflare-api-token>
```

The network must also have `dns_zone_id` and `dns_domain_suffix` set — configurable via `POST/PATCH /api/networks`. Master will return `422` if exposure is enabled on a server whose network has no DNS
zone configured.

### Planned DNS Enhancements

- **Route53 and other providers** — the `DnsProvider` interface is ready; adding a provider requires implementing three methods (`createARecord`, `updateARecord`, `deleteARecord`) and registering it
  in the factory.
- **Per-network credential sets** — currently all networks share global credentials. Independent credential sets per network require a secret storage strategy outside the database and are future work.
- **Frontend UI for network DNS configuration** — zone ID and domain suffix are currently set via API only; a UI panel on the Network settings page is planned.

## DNS on Migration

When a server is migrated to a different node, master updates the **managed** A record to point to the destination node's IP after the new container is running. With a 60-second TTL, players experience at most a short retry window before resolving the new address. See [Migration](../migration/index.md) for the full sequence.

**Custom domains are not updated on migration.** The `custom_hostname` column travels with the server row so mc-router on the new node picks it up automatically. However, the user's own A/CNAME record still points at the old node's IP until the user updates it manually in their DNS provider. See the caveat in [Custom domains](#custom-domains-bring-your-own-dns) above.
