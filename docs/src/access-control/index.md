# Access Control

Access control is implemented as a **permission node** system. Permissions are collected into groups, and groups are assigned to users either globally or scoped to a specific server or server network.

## Permission Nodes

| Permission Node    | Description                                                                |
|--------------------|----------------------------------------------------------------------------|
| `system.settings`  | Global application configuration, resource limits, and system settings. DNS integration is configured at deployment time via env vars — see [Enabling Public Hostnames](../usage/enabling-public-hostnames.md). |
| `system.users`     | Create, edit, and delete users and manage their group assignments                  |
| `system.groups`    | Create, edit, and delete user groups and assign their permissions                  |
| `system.nodes`     | Register, configure, and decommission nodes                                |
| `system.alerts`    | Manage alert thresholds and view alert events                                      |
| `server.create`    | Create new server instances                                                |
| `server.delete`    | Delete server instances and their data                                     |
| `server.start`     | Start a stopped server                                                     |
| `server.stop`      | Stop a running server                                                      |
| `server.force_stop`| Force-stop a server that is stuck (SIGKILL)                                 |
| `server.restart`   | Restart a running server                                                   |
| `server.configure` | Edit server configuration (properties, env vars, type, version)            |
| `server.resources` | Change RAM and CPU allocation for a server                                 |
| `server.files`     | Access the file explorer and editor for a server's data directory          |
| `server.mods`      | Manage the mod/plugin list via Modrinth search                             |
| `server.console`   | View live console output and send commands                                 |
| `server.export`    | Download a full instance archive                                           |
| `server.backup`    | Trigger manual backups and manage backup retention                         |
| `server.migrate`   | Move a server between nodes                                                |
| `server.view`      | Read-only access: status, config, logs, player count                       |
| `server.expires`   | Set or remove a server's expiration date                                    |
| `server.disable`   | Disable or re-enable a server (synchronous stop if running)                |
| `network.view`     | List/view server networks (GLOBAL or scoped to a specific network)         |
| `network.create`   | Create new server networks                                                 |
| `network.configure`| Update a network's name or description (scoped to a network)               |
| `network.delete`   | Delete a network and detach its member servers (scoped to a network)       |

Associating a server with a network additionally requires `network.view` on the target network.

## Default Groups

The following groups are pre-configured on installation. Administrators may create additional groups with any combination of permission nodes.

| Group            | Permissions                                                        |
|------------------|--------------------------------------------------------------------|
| **Super Admin**  | All permission nodes                                               |
| **Server Admin** | All except `system.settings`, `system.users`, `system.nodes`, `server.resources`, `server.migrate` |
| **Operator**     | `server.restart`, `server.console`, `server.view`, `server.backup` |
| **Viewer**       | `server.view`                                                      |

Groups may be assigned **globally** (applies to all servers) or **scoped per server or network** — a user can be Server Admin on Network A and Viewer on Network B simultaneously.

## Authentication

Authentication is handled natively within the master backend. There are no external identity provider dependencies.

- Passwords hashed with **Argon2id**
- **JWT access tokens** — short-lived (15 minutes)
- **Refresh tokens** — long-lived, stored in `HttpOnly` cookies, rotated on use
- First-run setup creates a Super Admin account
- Session invalidation supported (revoke all sessions for a user)
