# Server Management

## Supported Server Types

CraftPanel supports all server types available in the itzg images.

**Game servers** (`itzg/minecraft-server`):
Vanilla, Paper, Fabric, Folia, Forge, NeoForge, Quilt, Spigot

**Proxy servers** (`itzg/mc-proxy`):
Velocity, BungeeCord, Waterfall

**Utility** (`itzg/minecraft-server`):
Limbo

## Server Creation

Server creation requires the `server.create` permission. The creation wizard collects:

- Display name and optional description
- Server type (see above)
- Minecraft version — populated dynamically from the Mojang / itzg version API
- Target node — administrator selects; dynamic allocation is a future enhancement
- RAM allocation (slider within the user's permitted range)
- CPU share limit
- Optionally: assign to an existing [Server Network](./networks.md) or create a new one
- Optionally: expose externally via mc-router (triggers subdomain selection — see [Networking](../networking/index.md))

## Server Lifecycle

| Action    | Permission         | Behaviour                                                                                                                              |
|-----------|--------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| Start     | `server.start`     | Creates and starts the container with the current spec; pulls a new image if `mc_version` or `itzg_image_tag` changed since last start |
| Stop      | `server.stop`      | Stops the container; data is preserved                                                                                                 |
| Restart   | `server.restart`   | Stop + start; recreates container if `mc_version` or `itzg_image_tag` changed since last start                                         |
| Configure | `server.configure` | Update `mc_version` or `itzg_image_tag` via `PATCH /servers/{id}`; changes take effect on next start                                   |
| Delete    | `server.delete`    | Stops container and permanently removes data directory                                                                                 |

!!! warning
Delete is irreversible. Master will prompt for confirmation and require the server to be stopped first.
