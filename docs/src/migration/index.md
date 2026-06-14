# Server Migration

Moving a server between nodes is a first-class operation orchestrated by master. The design minimises player-facing downtime by using a live rsync pass before the final cutover.

Requires `server.migrate` permission.

## Migration Steps

| Step | Action                                                                                                                                               |
|------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | Administrator initiates migration in the master UI, selecting the source and destination node                                                        |
| 2    | Master allocates an rsync port on the destination node and instructs the destination agent to start an rsync receiver                                |
| 3    | Master instructs the source agent to begin a live `rsync` of the server's data directory to the destination (server remains running throughout)      |
| 4    | Master broadcasts a configurable warning to in-game players via RCON (e.g. *"Server restarting in 60 seconds"*)                                      |
| 5    | Master **stops** the source container and waits for the `STOPPED` confirmation (a clean stop flushes world data to disk). The container is **kept**  |
| 6    | Master instructs the source agent to perform a final incremental `rsync` — now from the **stopped** container, guaranteeing a consistent snapshot    |
| 7    | Master **removes** the source container and waits for confirmation, freeing the container name before the destination container is created           |
| 8    | Master creates the new container spec on the destination node, starts it, and waits for it to report `HEALTHY`                                        |
| 9    | Master updates the server's DNS A record to the destination node's IP via the DNS provider API                                                       |
| 10   | The mc-router label is set on the new container (at creation in step 8); mc-router on the destination node begins accepting connections              |
| 11   | Master updates the server's node assignment and port registry in the database                                                                        |

### Why stop before the final rsync

The final `rsync` runs only **after** the source container has stopped. A running Minecraft server holds unflushed world state in memory; copying its data directory live can capture a torn snapshot. Stopping first
(then syncing) guarantees the destination receives exactly the on-disk state the source had at shutdown — no writes are lost in the cutover window. The `save-all`/`save-off` RCON dance used by earlier versions is no
longer needed, because a clean stop already flushes and quiesces the world.

The source container is **stopped but not removed** until the final rsync completes, so the migration can restart it (rollback) if the rsync or destination start-up fails. Removal is awaited before the destination
container is created so the two never hold the same container name simultaneously — this also guarantees a server is never live on two nodes at once.

## DNS Propagation

DNS TTL for per-server A records is set to **60 seconds**. During the TTL window after step 9, some DNS resolvers may still return the old node IP. Players already in-game are disconnected at step 5 when the source
stops, regardless of DNS. Clients retrying will resolve the new IP within the TTL window. This is an acceptable trade-off that avoids any centralised traffic proxy.

## Server Network Impact

When a server that belongs to a Server Network is migrated to a different node:

- **Easy-mode proxy config** — master automatically updates the backend address from Docker hostname to `new-node-ip:port`
- **Manual-mode proxy config** — master displays a warning that the proxy config references the old address and must be updated manually before the proxy is restarted

## Downtime Window

The expected player-visible downtime is the time between steps 5 and 8 — the source stop, the final rsync delta, the source removal, and the destination container start. Typically under a minute for a small delta.
Total migration time depends on the size of the data directory and network bandwidth between nodes.
