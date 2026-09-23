# Upgrading

CraftPanel runs from versioned Docker images. Upgrading means deploying a new image tag and
restarting the stack — master applies any database schema changes automatically on startup.

## Before you upgrade

Back up the pieces that cannot be regenerated:

| What                     | Where                                       | Why                                                                 |
|--------------------------|---------------------------------------------|---------------------------------------------------------------------|
| PostgreSQL database      | the `db-data` volume                        | Rollback target; contains all users, servers, and configuration.    |
| `FORWARDING_KEY`         | your `.env` / secret store                  | Losing it makes stored proxy-forwarding secrets undecryptable.      |
| `master-certs` volume    | the `master-certs` volume                   | Keeps agents trusting master without re-registering.                |
| `agent-config` volume    | each node's `agent-config` volume           | Contains the node key; losing it forces re-registration.            |

A simple database dump from the running stack:

```bash
docker compose exec db pg_dump -U craftpanel craftpanel > craftpanel-backup-$(date +%F).sql
```

## Upgrade the stack

1. Pin the target version in `.env` (or pass it per command):

   ```bash
   IMAGE_VERSION=1.2.0
   ```

2. Pull the new images and recreate the services:

   ```bash
   docker compose pull
   docker compose up -d
   docker compose ps
   ```

3. Watch master come up. On startup it runs the schema migrator against every table and applies
   any missing columns/indexes idempotently, then seeds the built-in system groups. No manual
   migration step is required.

   ```bash
   docker compose logs -f master
   ```

`master`, `frontend`, and `agent` are all pinned by the same `IMAGE_VERSION`. To upgrade only the
agents (for example, roll out a new agent without touching master), set the tag on the agent
service or redeploy the agent container on each node with the new tag.

## Upgrading nodes

Each node runs its own agent container. After pulling the new agent image, restart the agent:

```bash
docker compose pull agent
docker compose up -d agent
```

The agent reconnects to master using its persisted node key (`agent-config` volume) — no
re-registration or re-trusting is needed, and running Minecraft server containers are left in
place. Master reconciles node state from the agent's first `NodeStateSnapshot` on reconnect.

## Rollback

Set `IMAGE_VERSION` back to the previous value and `docker compose up -d` again. **Schema migrations
are forward-only** — a newer master may add columns that an older master does not expect, and data
written by the newer version may not be readable by the older one. If you need to roll back across
a schema change, restore the database dump taken before the upgrade.

## Related

- [Environment Variables, Ports & Volumes](environment-variables.md) — `IMAGE_VERSION` and the rest
- [Deployment](deployment.md) — initial setup
- [Troubleshooting](troubleshooting.md) — startup failures
