# Server Migration

Moving a server between nodes is a first-class operation orchestrated by master. The design minimises player-facing downtime by using a live rsync pass before the final cutover.

Requires `server.migrate` permission.

## Migration Steps

| Step | Action |
|---|---|
| 1 | Administrator initiates migration in the master UI, selecting the source and destination node |
| 2 | Master instructs the source agent to begin a live `rsync` of the server's data directory to the destination node (server remains running throughout) |
| 3 | `rsync` completes its initial pass; master waits for confirmation from the destination agent |
| 4 | Master broadcasts a configurable warning to in-game players via RCON (e.g. *"Server restarting in 60 seconds"*) |
| 5 | Master instructs source agent: send RCON `save-all`, then `save-off` |
| 6 | Master instructs source agent to perform a final incremental `rsync` (delta only — fast) |
| 7 | Master creates the new container spec on the destination node and starts the container |
| 8 | Master updates the server's DNS A record to the destination node's IP via the DNS provider API |
| 9 | The mc-router label is set on the new container; mc-router on the destination node begins accepting connections |
| 10 | Master stops and removes the old container on the source node |
| 11 | Master updates the server's node assignment in the database |

## DNS Propagation

DNS TTL for per-server A records is set to **60 seconds**. During the TTL window after step 8, some DNS resolvers may still return the old node IP. Players already in-game are disconnected at step 10 regardless of DNS. Clients retrying will resolve the new IP within the TTL window. This is an acceptable trade-off that avoids any centralised traffic proxy.

## Server Network Impact

When a server that belongs to a Server Network is migrated to a different node:

- **Easy-mode proxy config** — master automatically updates the backend address from Docker hostname to `new-node-ip:port`
- **Manual-mode proxy config** — master displays a warning that the proxy config references the old address and must be updated manually before the proxy is restarted

## Downtime Window

The expected player-visible downtime is the time between steps 5 and 9 — typically under 30 seconds for the final rsync delta plus container start time. Total migration time depends on the size of the data directory and network bandwidth between nodes.
