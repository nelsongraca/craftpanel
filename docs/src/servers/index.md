# Server Management

## Supported Server Types

The backend `ServerType` enum is the single source of truth for the accepted types (it is exposed
on the OpenAPI spec, and the UI builds its type picker from it). It is a curated set — CraftPanel
only provisions types it can classify.

**Game servers** (`itzg/minecraft-server`):
Vanilla, Paper, Purpur, Folia, Spigot, Bukkit, Fabric, Forge, NeoForge, Quilt, Limbo, Custom

**Proxies** (`itzg/mc-proxy`):
Velocity, BungeeCord, Waterfall

**Utility** (`ghcr.io/quozul/picolimbo`):
PicoLimbo

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
| Change type | `server.configure` | Swap the server software via `PATCH /servers/{id}` with `server_type`; server must be stopped, see [below](#changing-server-type)      |
| Delete    | `server.delete`    | Stops container and permanently removes data directory                                                                                 |

!!! warning
Delete is irreversible. Master will prompt for confirmation and require the server to be stopped first.

### Changing server type

You can change an existing server's software without recreating it. The picker (in the server's
**Overview** tab) is populated from the backend `ServerType` enum.

- The server must be **stopped**.
- If enabled mods or plugins are installed, you cannot switch between a mod-loader type and a
  plugin type (or to a type that loads neither) — remove them first.
- A proxy with backends cannot become a non-proxy type, and a proxy backend cannot switch to a
  type that doesn't support forwarding.
- Type-exclusive settings (custom jar, listen port, config mode, stop command, proxy settings) are
  reset to the new type's defaults; the data directory is **not** moved, so existing world files
  may need manual cleanup to suit the new software.
- For `CUSTOM`, the same form shows the current custom jar plus the **Disable healthcheck** and
  **Force redownload on start** toggles, and requires a jar when the target type is `CUSTOM`.
