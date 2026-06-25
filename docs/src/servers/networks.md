# Server Networks

## Concept

A **Server Network** is a logical grouping of servers — typically one proxy and one or more backend game servers — that form a single player-facing Minecraft network. Networks are created and managed
by administrators; they are not self-service for end users.

## Docker Networking

Servers within a network that **share the same node** are placed on a dedicated Docker bridge network. The proxy reaches backends by container hostname without any ports exposed on the host.

Servers in the same network on **different nodes** communicate over the private network between nodes, using the host IP and the backend's assigned port from the port registry. Backend ports are bound
on the host interface but firewalled from public access.

```
Same node                         Cross-node
─────────────────────────         ─────────────────────────────────────
Docker bridge: net_<id>           Node 1              Node 2
[velocity] ──▶ [survival]         [velocity] ──▶      [survival]
               [creative]          node1 internal      node2-ip:25571
                                   hostname
```

## Port Registry

Master maintains a port registry per node. Each server requiring a host-mapped port (proxies, standalone servers, cross-node backends) is assigned a port from a configurable range (default
`25570–26070`) at creation time.

These ports are internal implementation details — end users only ever see the public hostname. Ports are reclaimed when a server is deleted.

## Constraints

- All servers in a network may reside on the same node or different nodes — the administrator decides placement at creation time
- Docker bridge networking is only available between containers on the same node
- Cross-node communication within a network uses host IP + port automatically; no manual configuration is required in easy mode
- Migrating a server to a different node updates cross-node addressing automatically for easy-mode proxies

## Cross-Node Constraints

Cross-node Server Networks require Docker Swarm. Two conditions must both be met:

1. **Master must have `DOCKER_ENDPOINT` configured** — this enables master to create and manage overlay networks. Without it, cross-node Server Networks are rejected.
2. **All nodes in the network must be joined to a Swarm** — the `swarm_active` flag on each node is set from the agent's `NodeStateSnapshot` on every connect.

If either condition is not met, assigning a server to a cross-node network (or creating a Server Network with servers across nodes when master has no Docker endpoint) returns:

- `422 Unprocessable Entity` — `"Master is not configured with a Docker endpoint — Swarm mode required for cross-node Server Networks"`
- `422 Unprocessable Entity` — `"Node(s) <names> are not joined to a Swarm — join all nodes to a Swarm before creating cross-node Server Networks"`

When all members of a Server Network are on the same node, no Swarm is required — the agent creates a local bridge network.

## Standalone Server Isolation

A server with no assigned Server Network is placed on a dedicated bridge named `craftpanel-server-<uuid>`. The agent creates this bridge when the server starts and deletes it when the server is removed. Standalone servers are reachable via mc-router (which attaches to the bridge) but isolated from other servers at the network layer.
