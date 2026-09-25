# Game containers leave the shared infra network; mc-router joins each server network

## Status

accepted

## Context

Every managed game container was attached to two Docker networks: its own Server Network bridge
(`craftpanel-net-<uuid>`) or standalone bridge (`craftpanel-server-<uuid>`), set as the container's
primary network mode, **and** the host-global `craftpanel` network. The shared attach predates
Server Networks: when `craftpanel` was the only network it was how mc-router reached backends. The
per-network bridge/overlay work was added later but never removed the shared attach, so Server
Networks were not actually isolated — every container shared one host-wide L2 segment.
`docs/src/networking/index.md` already described the intended isolation, so the code and docs
disagreed.

mc-router's Docker auto-discovery (`IN_DOCKER=true`) subscribes to the **local** daemon's event
stream. It therefore only ever discovers containers on its own node; overlay networks give it L3
reachability but not discovery. Ingress is inherently node-local: master points each exposed server's
DNS record at the node hosting it, whose router discovers the local container. Overlays exist for
proxy-to-backend application traffic, not for router routing.

## Decision

- **Game containers join only their own server network.** `DockerContainerManager.createContainer`
  no longer connects them to `craftpanel`. The `mc-router.network` label names the container's own
  network, so the router dials the backend there.
- **mc-router stays host-global and on `craftpanel`.** `craftpanel` remains the router's home (and
  the agent's path to it for player-count status), plus the network for rsync utility containers. It
  is no longer shared with game containers.
- **The router is attached to every server network on the node.** `ContainerOperator` attaches it
  before creating the container (so routing is ready when the label appears), and `RouterSupervisor`
  reconciles all managed networks on agent start and whenever the router is created/recreated — a
  fresh router has no per-server attachments.
- **Overlays are master-owned.** The agent manages bridges only and never deletes an `overlay`
  network; master creates and removes Server Network overlays.
- **Legacy containers migrate lazily.** `ContainerSpecDiff` flags a container attached to a network
  beyond its spec (or carrying a stale `mc-router.network` label). Because a spec mismatch on a
  *running* container is deliberately deferred, the container is recreated on its next start/restart
  — no mass restart on upgrade.
- **Player count is decoupled from routing.** It is probed with `docker exec <container> mc-monitor
  status --json` (with `--use-proxy` for a PROXY-protocol proxy, driven by a new
  `StartContainerCommand.proxy_protocol` field), not by pinging mc-router.

## Consequences

- Server Network bridges/overlays now provide real container-level isolation; the only shared
  surface is mc-router, which only proxies inbound Minecraft connections.
- A router on a busy node is attached to one network per Server Network/standalone server it serves
  (O(servers) endpoints), instead of a single shared network. Accepted; a routing gap after a router
  recreate is closed by the supervisor's reconciliation.
- Ingress stays node-local. Single-entry cross-node routing (one router forwarding to a backend on
  another node) is explicitly out of scope; it would require Swarm services plus `IN_DOCKER_SWARM`,
  or master-pushed static routes.
- A container that never restarts stays on `craftpanel` until its next restart.

## Rejected alternatives

- **Keep the shared network and document isolation as best-effort.** Simplest, and what ADR 0007's
  "one-per-host infrastructure" framing implied, but leaves Server Networks unisolated; the
  documented model was already the isolated one.
- **Router self-derives `--use-proxy`** by reading the applied proxy config or by probing with a
  fallback. Reading couples the agent to master's patch format or needs a TOML/YAML scan; probing
  wastes a call per poll. An explicit spec field is cheaper and unambiguous.
