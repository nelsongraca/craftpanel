# Server Networks

## Concept

A **Server Network** is a logical grouping of servers — typically one proxy and one or more backend game servers — that form a single player-facing Minecraft network. Networks are created and managed by administrators; they are not self-service for end users.

## Docker Networking

Servers within a network that **share the same node** are placed on a dedicated Docker bridge network. The proxy reaches backends by container hostname without any ports exposed on the host.

Servers in the same network on **different nodes** communicate over the private network between nodes, using the host IP and the backend's assigned port from the port registry. Backend ports are bound on the host interface but firewalled from public access.

```
Same node                         Cross-node
─────────────────────────         ─────────────────────────────────────
Docker bridge: net_<id>           Node 1              Node 2
[velocity] ──▶ [survival]         [velocity] ──▶      [survival]
               [creative]          node1 internal      node2-ip:25571
                                   hostname
```

## Port Registry

Master maintains a port registry per node. Each server requiring a host-mapped port (proxies, standalone servers, cross-node backends) is assigned a port from a configurable range (default `25570–26070`) at creation time.

These ports are internal implementation details — end users only ever see the public hostname. Ports are reclaimed when a server is deleted.

## Constraints

- All servers in a network may reside on the same node or different nodes — the administrator decides placement at creation time
- Docker bridge networking is only available between containers on the same node
- Cross-node communication within a network uses host IP + port automatically; no manual configuration is required in easy mode
- Migrating a server to a different node updates cross-node addressing automatically for easy-mode proxies
