# Access Control

## Enums

### `permission_node`

```
system.settings  system.users     system.nodes
server.create    server.delete    server.start      server.stop
server.restart   server.configure server.resources  server.files
server.mods      server.console   server.export     server.backup
server.upgrade   server.migrate   server.view
```

### `assignment_scope`

| Value | Meaning |
|---|---|
| `GLOBAL` | Assignment applies to all servers and networks |
| `SERVER` | Assignment applies to one specific server |
| `NETWORK` | Assignment applies to all servers in one network |

---

## `groups`

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `name` | VARCHAR(64) | Unique display name |
| `is_system` | BOOLEAN | `true` = pre-configured default group; cannot be deleted |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

### Default groups

| Group | Permissions |
|---|---|
| Super Admin | All permission nodes |
| Server Admin | All except `system.settings`, `system.users`, `system.nodes`, `server.resources`, `server.migrate` |
| Operator | `server.restart`, `server.console`, `server.view`, `server.backup` |
| Viewer | `server.view` |

---

## `group_permissions`

One row per permission granted to a group.

| Column | Type | Description |
|---|---|---|
| `group_id` | UUID | FK → `groups`, CASCADE DELETE |
| `permission` | `permission_node` ENUM | |

**Primary key:** `(group_id, permission)`

---

## `user_group_assignments`

Assigns a group to a user, either globally or scoped to a specific server or network. A user may have multiple assignments — for example, Server Admin on Network A and Viewer on Network B simultaneously.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `user_id` | UUID | FK → `users`, CASCADE DELETE |
| `group_id` | UUID | FK → `groups`, CASCADE DELETE |
| `scope_type` | `assignment_scope` ENUM | `GLOBAL`, `SERVER`, or `NETWORK` |
| `scope_id` | UUID | `NULL` if `GLOBAL`; server or network ID otherwise |
| `created_at` | TIMESTAMPTZ | |

**Unique constraint:** `(user_id, group_id, scope_type, scope_id)`

!!! note
    Permission resolution: a user's effective permissions for a given server are the union of all their global assignments plus any assignments scoped to that server or its network.
