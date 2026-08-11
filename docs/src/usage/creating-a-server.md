# Creating a Server

From the **Server List** screen, click **New Server** and provide:

- **Node** — which active node hosts the server (see [Adding a Node](adding-a-node.md))
- **Server type & version** — e.g. Vanilla, Paper, Forge; the Docker image and `VERSION` env var are derived from this
- **Resources** — RAM and CPU allocation, checked against the node's available capacity
- **Server Network** — optional; group this server with others behind a shared proxy, so they can be reached through one hostname/port via mc-router or a Velocity/BungeeCord proxy

Once created, the server is manageable from its detail page.

## Configuration

The **Configuration** tab edits `server.properties` and environment variables. Managed fields (difficulty, gamemode, MOTD, etc.) are exposed as form controls; anything else can be set as a raw env var passed to the `itzg/minecraft-server` image. Changes apply on next server restart.

## Mods & Plugins

The **Mods** tab searches and installs from Modrinth directly — pick a mod, pick a compatible version for the server's loader/MC version, install. Installed mods can be updated or removed from the same screen.

## File Explorer & Console

The **Files** tab is a browser-based file manager for the server's data directory (upload, download, edit, delete, move). The **Console** tab is a live stdin/stdout stream — type commands, watch log output in real time.

## Backups

The **Backups** tab triggers on-demand backups and lists existing ones for restore or download. Each backup snapshots the server's full data directory at that point in time.

## Start / stop / restart

Use the controls on the server detail page. Status (stopped/starting/healthy/unhealthy) updates live via the node agent's heartbeat.
