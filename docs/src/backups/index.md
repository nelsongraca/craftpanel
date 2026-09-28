# Backups

## Backup Process

Backups are performed by the node agent on instruction from master. The sequence ensures world data integrity:

1. Agent sends RCON `save-all` followed by `save-off` to the server
2. Agent creates a compressed archive (`tar.gz`) of the server's data directory bind mount
3. Agent sends `save-on` to re-enable auto-save
4. Agent reports backup metadata (timestamp, file size, storage path) to master
5. Master stores the metadata in the database and enforces the retention policy

## Manual Backups

Users with `server.backup` permission may trigger a backup at any time from the server detail page.

## Automated Backups

Automated backup schedules are configured per server using a **cron expression**. Master's internal scheduler evaluates due jobs and dispatches backup instructions to the relevant node agent.

Example expressions:

| Expression    | Schedule                |
|---------------|-------------------------|
| `0 4 * * *`   | Daily at 04:00          |
| `0 */6 * * *` | Every 6 hours           |
| `0 3 * * 0`   | Weekly, Sunday at 03:00 |

The scheduler is handler-based — adding new job types (e.g. scheduled restarts, RCON commands) requires only implementing a handler and registering it; the tick loop and deduplication logic are
shared.

## Scheduled Jobs

Beyond backups, servers can have arbitrary cron-scheduled jobs. Definitions are stored in
`server_jobs` and evaluated by the same internal scheduler (one tick per minute, with per-minute
de-duplication).

| Type           | Action                                                            |
|----------------|-------------------------------------------------------------------|
| `START`        | Start the server if it is stopped                                 |
| `STOP`         | Gracefully stop the server if it is running                       |
| `RESTART`      | Restart a running server                                          |
| `RCON_COMMAND` | Run an arbitrary console command via RCON (requires `payload`)    |

REST API (`server.cron` permission to read/manage; **creating** a job additionally requires the
action's own permission — `server.start`, `server.stop`, `server.restart`, or `server.console`):

```
GET    /api/servers/{id}/jobs          list a server's jobs
POST   /api/servers/{id}/jobs          create a job
PATCH  /api/servers/{id}/jobs/{jobId}  update cron / payload / enabled
DELETE /api/servers/{id}/jobs/{jobId}  delete a job
GET    /api/system/job-types           enumerate schedulable types
```

`RCON_COMMAND` jobs run `rcon-cli <command>` inside the container on the node agent. The command is
fire-and-forget (no result is reported back), so a job is skipped unless the server reports
`HEALTHY`. The agent enforces this; the command text is validated (single line, ≤ 512 chars) at the
API boundary.

Still planned: per-job execution history (last-run result/duration) and missed-fire recovery after
master downtime.

## Retention Policy

Each server has a **maximum backup count** limit (configurable per user or group, default 10). Before creating a new backup, master checks the current count. If the limit is reached, the oldest backup
is deleted — the agent removes the file and master removes the metadata record.

A hard disk-usage limit may additionally be configured at the node level as a secondary safeguard.

## Storage

Backups are stored on the node that hosts the server, in a directory separate from the live data:

```
/data/craftpanel/backups/<server-id>/
```

The architecture includes a defined interface for offloading completed backups to **S3-compatible object storage** (Backblaze B2, MinIO, AWS S3). This is a planned future enhancement.

## Export

Users with `server.export` permission may download the most recent backup (or trigger a fresh one) as a file download streamed via master. The resulting archive is a complete, portable snapshot of the
server instance suitable for restoration elsewhere or offline storage.
