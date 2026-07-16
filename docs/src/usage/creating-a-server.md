# Creating a Server

From the **Server List** screen, click **New Server** and provide:

- **Node** — which active node hosts the server (see [Adding a Node](adding-a-node.md))
- **Server type & version** — e.g. Vanilla, Paper, Forge; the Docker image and `VERSION` env var are derived from this
- **Resources** — RAM and CPU allocation, checked against the node's available capacity
- **Server Network** — optional; group this server with others behind a shared proxy (see [Server Networks](../servers/networks.md))

Once created, the server is manageable from its detail page: start/stop/restart, live console, file explorer, mods, backups, and configuration. See the reference pages below for each area.

## Next steps

- [Configuration](../servers/configuration.md) — server properties, env vars, managed vs. manual config
- [Mods & Plugins](../servers/mods.md) — Modrinth integration
- [File Explorer & Console](../servers/files-console.md)
- [Backups](../backups/index.md)
