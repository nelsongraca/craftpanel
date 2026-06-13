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

## Scheduled Jobs (Planned)

The following scheduler features are planned but not yet implemented:

- **User-defined jobs REST API** — `GET/POST/PATCH/DELETE /api/servers/{id}/jobs` allowing users with `server.configure` permission to create arbitrary scheduled jobs per server. Supported job types
  are backed by registered handlers; `GET /api/system/job-types` will enumerate available types.
- **Additional built-in job types** — `RESTART` and `RCON_COMMAND` are the initial candidates beyond `BACKUP`.
- **Per-job execution history** — audit log of last-run time, result, and duration per job row.
- **Missed-fire recovery** — if master was offline during a scheduled window, jobs that were missed can optionally be fired on the next startup.

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
