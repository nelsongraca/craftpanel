# Data Model

CraftPanel's persistent state lives entirely in PostgreSQL on the master node. The agent holds no durable state — master can fully reconstruct the system view from the database after a restart.

## Conventions

- All tables have `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `created_at TIMESTAMPTZ` and `updated_at TIMESTAMPTZ` on all mutable entities
- Hard deletes throughout — no soft delete pattern; cascades are defined per relationship
- Enums are defined as Postgres `ENUM` types and mirrored as Kotlin sealed classes

## Sections

| Section | Tables |
|---|---|
| [Users & Auth](users-auth.md) | `users`, `refresh_tokens` |
| [Access Control](access-control.md) | `groups`, `group_permissions`, `user_group_assignments` |
| [Nodes](nodes.md) | `nodes`, `node_metrics` |
| [Servers](servers.md) | `servers`, `server_env_vars`, `server_container_metrics`, `port_registry`, `proxy_backends` |
| [Server Networks](server-networks.md) | `server_networks` |
| [Mods](mods.md) | `server_mods` |
| [Backups](backups.md) | `backups` |
| [Migrations](migrations.md) | `migrations`, `migration_step_log` |
| [Alerts](alerts.md) | `alert_thresholds`, `alert_events` |
| [System Settings](system-settings.md) | `system_settings` |

## What Lives Outside the Database

Deployment-time configuration is **not** stored in the database. This includes database credentials, JWT signing keys, DNS provider API keys, TLS certificate paths, and bind addresses. These are provided via config file, environment variables, or mounted secrets. See [Configuration & Secrets](../tech-stack/configuration.md) for the full breakdown.
