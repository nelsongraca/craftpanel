# Server Configuration

## Configuration Modes

Each server operates in one of two configuration modes, switchable by a user with `server.configure` permission.

### Managed Mode (default)

`server.properties` values are driven by **itzg environment variables**. The UI presents labelled form fields — users never interact with raw env var names. Master translates UI inputs to the correct itzg env var format, stores them in the database, and injects them into the container spec on creation or restart.

This is the recommended mode for most users.

### Manual Mode

Disables env var management entirely. The panel provides direct file editor access to `server.properties` (and any other config file) via the [File Explorer](./files-console.md). The user is responsible for file correctness.

!!! warning
    Switching from managed to manual mode presents a confirmation warning. Managed mode settings are preserved in the database but are no longer applied to the container until managed mode is re-enabled.

## Proxy Configuration (Velocity / BungeeCord)

Proxy server configuration follows the same two-mode pattern.

### Easy Mode

The UI presents a list of backend servers available in the same Server Network. The user selects which servers the proxy connects to and assigns internal names. Master generates the correct `velocity.toml` or `config.yml` from this data.

Backend address resolution is automatic:

- **Same node as proxy** — Docker container hostname is used (bridge network, no port exposure needed)
- **Different node** — Node's private IP and the backend's assigned host port are used

### Manual Mode

Full file editor access to the proxy config file. Easy mode selections are ignored. The administrator is responsible for keeping the config consistent with the actual server topology.

!!! note
    When a server is migrated to a different node, master automatically updates easy-mode proxy configs that reference it. Manual-mode proxy configs are not updated automatically — master will display a warning.

## Minecraft Version

The Minecraft version is set at server creation and stored as the `VERSION` itzg env var. It can be changed via `server.configure` permission. The change takes effect on the next restart.

## itzg Image Version

The itzg image tag used for the container is managed separately via the `server.upgrade` permission. Upgrading pulls the latest tag and recreates the container.
