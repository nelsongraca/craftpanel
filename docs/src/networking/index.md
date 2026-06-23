# Networking & Player Ingress

## mc-router

Each node runs one instance of [`itzg/mc-router`](https://github.com/itzg/mc-router). It listens on port **25565** and routes incoming Minecraft connections by hostname, reading its routing table from
**Docker container labels**. Master sets the appropriate labels when creating or updating containers — no direct API communication between master and mc-router is required.

Game traffic flows directly from players to mc-router to containers. It never passes through the master backend.

### Lifecycle management

The **agent** provisions and manages the mc-router container automatically — no manual setup is required on the node. On every startup the agent:

1. Pulls the configured mc-router image (controlled by `MCROUTER_IMAGE`, default `itzg/mc-router:latest`)
2. Starts the container if it is not already running, or leaves it in place if it is

The pull step runs by default so nodes always run the latest mc-router release. It can be disabled by setting `MCROUTER_UPDATE_ON_START=false`, which causes the agent to use whatever image is already
cached locally — useful when the image is pinned to a specific digest or when image pulls are restricted. See [Agent Configuration](../nodes/index.md#agent-configuration) for the full list of env
vars.

## Docker Network

All server containers, the mc-router container, and the agent container must share a common Docker bridge network so that mc-router can reach game server containers and the agent can query player
counts via the Minecraft Server Query protocol.

### The `craftpanel` network

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

The agent attaches every game server container to this network at creation time in addition to any server network bridge the container belongs to. No manual configuration is required for individual
servers.

### Security note

All containers on the `craftpanel` network can reach each other directly by container name. This is an inherent property of a shared Docker bridge network. On a multi-tenant node hosting servers for
different trust levels, this is a known limitation. Network-policy-level isolation between containers on a shared bridge is not available in plain Docker without external tooling. This is accepted for
the current architecture and noted for future improvement.

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

When a server is migrated to a different node, master updates the A record to point to the destination node's IP after the new container is running. With a 60-second TTL, players experience at most a
short retry window before resolving the new address. See [Migration](../migration/index.md) for the full sequence.
