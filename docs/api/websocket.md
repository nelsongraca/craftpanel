# WebSocket Events

The browser maintains up to two WebSocket connections to master:

- **Dashboard socket** — always-on, server-push only. Delivers live updates for all resources the user has access to.
- **Console socket** — opened on demand when a user opens a server console. Bidirectional. Torn down when the console is closed.

---

## Authentication

WebSocket connections are authenticated via the existing `refresh_token` HttpOnly cookie. The browser sends it automatically on the upgrade request since the frontend and API are same-origin.

Master validates the refresh token on upgrade. If invalid or revoked the upgrade is rejected with `401`.

**Session revalidation:** master revalidates the refresh token and re-resolves the user's permissions every 5 minutes on each open connection. If the token has been revoked or permissions have changed the connection is closed immediately on the next revalidation tick. This gives a maximum 5-minute window between a session revocation and connection termination — consistent with the 15-minute JWT access token TTL on the REST API.

---

## Message envelope

All messages on both sockets share the same top-level envelope:

```json
{
  "type": "<event_type>",
  "payload": {}
}
```

`type` identifies the event. `payload` is event-specific. Unknown `type` values should be ignored by the client — master may introduce new event types in future versions.

---

## Dashboard socket

**Endpoint:** `wss://<host>/api/v1/ws`

Server-push only. Master filters all events to resources the authenticated user has at least `server.view` permission on. The client receives no events for servers or nodes it cannot access.

The client sends no messages on this socket. Any message received from the client is ignored.

### Connection lifecycle

On connect master immediately pushes a snapshot of current state so the UI can render without waiting for the first update cycle:

```json
{
  "type": "snapshot",
  "payload": {
    "servers": [ ],
    "nodes": [ ]
  }
}
```

Each entry in `servers` and `nodes` matches the shape of the corresponding REST GET response object.

---

### Event types

#### `server.status`

Fired when a server's runtime status changes.

```json
{
  "type": "server.status",
  "payload": {
    "server_id": "<uuid>",
    "status": "HEALTHY",
    "container_id": "abc123def456",
    "is_migrating": false,
    "recorded_at": "2026-05-04T10:00:00Z"
  }
}
```

`status` values: `STOPPED`, `STARTING`, `HEALTHY`, `UNHEALTHY`.

`is_migrating` is included so the UI can update the migration indicator without a separate event.

---

#### `server.players`

Fired when the player count or player list changes. Refreshed every 30 seconds by master from the Minecraft query protocol.

```json
{
  "type": "server.players",
  "payload": {
    "server_id": "<uuid>",
    "player_count": 14,
    "player_list": ["Notch", "jeb_"],
    "recorded_at": "2026-05-04T10:00:00Z"
  }
}
```

---

#### `server.metrics`

Fired every 60 seconds with the latest container resource snapshot.

```json
{
  "type": "server.metrics",
  "payload": {
    "server_id": "<uuid>",
    "cpu_percent": 38.2,
    "ram_used_mb": 3200,
    "net_in_bytes": 204800,
    "net_out_bytes": 102400,
    "recorded_at": "2026-05-04T10:00:00Z"
  }
}
```

---

#### `server.backup.progress`

Fired periodically during an active backup.

```json
{
  "type": "server.backup.progress",
  "payload": {
    "server_id": "<uuid>",
    "backup_id": "<uuid>",
    "percent_complete": 42,
    "recorded_at": "2026-05-04T10:00:00Z"
  }
}
```

`percent_complete` is `-1` if the agent cannot determine progress.

---

#### `server.backup.complete`

Fired when a backup finishes, whether successful or not.

```json
{
  "type": "server.backup.complete",
  "payload": {
    "server_id": "<uuid>",
    "backup_id": "<uuid>",
    "status": "COMPLETED",
    "size_bytes": 524288000,
    "error_message": null,
    "completed_at": "2026-05-04T10:03:12Z"
  }
}
```

`status` values: `COMPLETED`, `FAILED`.

---

#### `server.migration.progress`

Fired periodically during an active rsync pass.

```json
{
  "type": "server.migration.progress",
  "payload": {
    "server_id": "<uuid>",
    "migration_id": "<uuid>",
    "is_final_pass": false,
    "bytes_transferred": 1073741824,
    "total_bytes": 2147483648,
    "percent_complete": 50,
    "phase": "sending incremental file list",
    "recorded_at": "2026-05-04T10:02:00Z"
  }
}
```

---

#### `server.migration.step`

Fired each time a migration step starts or completes.

```json
{
  "type": "server.migration.step",
  "payload": {
    "server_id": "<uuid>",
    "migration_id": "<uuid>",
    "step": 3,
    "status": "COMPLETED",
    "message": "Initial rsync complete — 2.1 GB transferred",
    "recorded_at": "2026-05-04T10:05:00Z"
  }
}
```

`status` values: `STARTED`, `COMPLETED`, `FAILED`.

---

#### `server.migration.complete`

Fired when a migration reaches a terminal state.

```json
{
  "type": "server.migration.complete",
  "payload": {
    "server_id": "<uuid>",
    "migration_id": "<uuid>",
    "status": "COMPLETED",
    "destination_node_id": "<uuid>",
    "error_message": null,
    "completed_at": "2026-05-04T10:08:43Z"
  }
}
```

`status` values: `COMPLETED`, `FAILED`.

---

#### `node.status`

Fired when a node's status changes. Only pushed to users with `system.nodes` permission.

```json
{
  "type": "node.status",
  "payload": {
    "node_id": "<uuid>",
    "status": "DEGRADED",
    "recorded_at": "2026-05-04T10:00:00Z"
  }
}
```

`status` values: `PENDING`, `ACTIVE`, `DEGRADED`, `DECOMMISSIONED`.

---

#### `node.metrics`

Fired every 60 seconds with the latest node resource snapshot. Only pushed to users with `system.nodes` permission.

```json
{
  "type": "node.metrics",
  "payload": {
    "node_id": "<uuid>",
    "cpu_percent": 42.3,
    "cpu_per_core": [38.1, 46.5, 40.2, 44.4],
    "ram_used_mb": 14200,
    "ram_total_mb": 32768,
    "net_in_bytes": 1048576,
    "net_out_bytes": 524288,
    "disk_used_bytes": 107374182400,
    "disk_total_bytes": 500107862016,
    "recorded_at": "2026-05-04T10:00:00Z"
  }
}
```

---

#### `alert.fired`

Fired when an alert threshold is crossed.

```json
{
  "type": "alert.fired",
  "payload": {
    "event_id": "<uuid>",
    "threshold_id": "<uuid>",
    "scope_type": "NODE",
    "scope_id": "<node-uuid>",
    "metric": "ram_percent",
    "message": "Node node-1: RAM at 94%",
    "fired_at": "2026-05-04T10:00:00Z"
  }
}
```

Node-scoped alerts are only pushed to users with `system.nodes` permission. Server-scoped alerts are only pushed if the user has `server.view` on the relevant server.

---

#### `alert.resolved`

Fired when an active alert condition clears.

```json
{
  "type": "alert.resolved",
  "payload": {
    "event_id": "<uuid>",
    "threshold_id": "<uuid>",
    "scope_type": "NODE",
    "scope_id": "<node-uuid>",
    "message": "Node node-1: RAM normalised",
    "resolved_at": "2026-05-04T10:15:00Z"
  }
}
```

---

## Console socket

**Endpoint:** `wss://<host>/api/v1/ws/console/{server_id}`

Opened when the user opens a server console. Bidirectional — master proxies input to the container stdin via the agent's gRPC data connection and streams container stdout/stderr back to the browser.

Requires `server.console` permission on the specified server. Upgrade is rejected with `403` if the user lacks this permission.

Only one console socket per server is supported at a time. If a second client attempts to open a console for the same server the upgrade is rejected with `409`.

### Client → server (input)

Raw text input from the browser terminal. Each message is one command.

```json
{
  "type": "console.input",
  "payload": {
    "data": "say Hello world\n"
  }
}
```

---

### Server → client (output)

Raw output from the container streamed back to the browser. May contain ANSI escape codes for colour.

```json
{
  "type": "console.output",
  "payload": {
    "data": "[10:00:01] [Server thread/INFO]: Hello world\n"
  }
}
```

---

### Connection lifecycle

On connect master sends a `console.ready` message once the agent confirms the Docker attach is established:

```json
{
  "type": "console.ready",
  "payload": {
    "server_id": "<uuid>"
  }
}
```

If the server stops while the console is open master sends `console.disconnected` and closes the socket:

```json
{
  "type": "console.disconnected",
  "payload": {
    "server_id": "<uuid>",
    "reason": "Server stopped"
  }
}
```

If the agent becomes unreachable while the console is open:

```json
{
  "type": "console.disconnected",
  "payload": {
    "server_id": "<uuid>",
    "reason": "Agent unreachable"
  }
}
```
