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

Wildcards are supported in permission checks using dot-separated prefix matching:

| Pattern | Matches |
|---|---|
| `*` | All permissions |
| `server.*` | All `server.*` permissions |
| `system.*` | All `system.*` permissions |

Wildcards are resolved at runtime — only explicit permission nodes are stored in `group_permissions`.

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
| `is_system` | BOOLEAN | `true` = pre-defined system group; cannot be deleted or renamed |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

### System groups

Pre-seeded at first boot. `is_system = true` — cannot be deleted or renamed.

| Group | Permissions |
|---|---|
| Super Admin | All permission nodes (`*`) |
| Server Admin | All except `system.settings`, `system.users`, `system.nodes`, `server.resources`, `server.migrate` |
| Operator | `server.restart`, `server.console`, `server.view`, `server.backup` |
| Viewer | `server.view` |

---

## `group_permissions`

One row per permission node granted to a group.

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
| `scope_id` | UUID | `NULL` if `GLOBAL`; server or network UUID otherwise |
| `created_at` | TIMESTAMPTZ | |

**Unique constraint:** `(user_id, group_id, scope_type, scope_id)`

---

## Permission Resolution

A user's effective permissions for a given resource are resolved as follows:

1. Fetch all `user_group_assignments` for the user where `scope_type = GLOBAL`
2. If the resource is a server, also fetch assignments where `scope_type = SERVER` and `scope_id = server_id`
3. If the resource belongs to a network, also fetch assignments where `scope_type = NETWORK` and `scope_id = network_id`
4. Union all permission nodes from the matched group assignments
5. Permissions are **additive only** — no deny rules exist

`is_active` on the user record is checked as part of every resolution query. Inactive users are rejected regardless of group assignments.

!!! note
    Permission resolution always hits the database. Caching may be added later if it becomes a bottleneck.
