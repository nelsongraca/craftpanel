# Data Persistence

## Server Data

Each server instance has a dedicated data directory on its host node, mounted into the container as a Docker bind mount:

```
/data/craftpanel/servers/<server-id>/
```

This directory contains all world data, configuration files, mod configs, logs, and anything else the itzg image writes to `/data` inside the container.

Bind mounts are used (rather than Docker volumes) for straightforward access, backup via standard filesystem tools, and simpler disaster recovery.

## Backup Storage

Backup archives are stored in a separate directory on the same node, outside the live data path:

```
/data/craftpanel/backups/<server-id>/
```

Keeping backups separate prevents accidental deletion when the live data directory is modified or removed.

## Master Database

PostgreSQL runs on the master node and is the authoritative store for all system state:

- Users, groups, and permission assignments
- Server definitions (type, version, config, mod list, env vars)
- Node registrations and port registry
- Server Network topology
- Backup metadata (timestamp, size, path per backup)
- Metric snapshots (1-minute intervals, configurable retention)
- DNS record IDs for master-managed A records

No server state is stored solely in agent memory. Master can reconstruct the full system view from the database after a restart.

## Recovery

Because all live data is in bind mounts and all metadata is in PostgreSQL:

- **Node failure** — master detects the node as unreachable; servers are marked offline. Data is on the node's disk. Once the node recovers (or data is copied to a new node via the migration flow), servers can be restarted.
- **Master failure** — running containers continue running unaffected; players stay connected. Once master restarts and reconnects to agents, the dashboard reflects current state again.
- **Full restore from backup** — export any backup archive, create a new server, and import the archive. Master handles the container setup; the archive provides the world data.
