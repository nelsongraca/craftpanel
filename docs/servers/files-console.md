# File Explorer & Console

## File Explorer

Requires `server.files` permission.

The file explorer is rooted at the server's data directory bind mount. Users cannot traverse outside their server's own directory.

**Features:**

- Tree view navigation
- Inline text editor (Monaco-based) with syntax highlighting for `.json`, `.toml`, `.yaml`, `.yml`, `.properties`, `.conf`
- Upload individual files
- Download individual files

!!! warning
    Changes saved via the file editor take effect immediately on disk. For config files read only at startup (like `server.properties` in manual mode), a restart is required for changes to apply in-game.

## Live Console

Requires `server.console` permission.

The console panel provides a real-time view of the container's stdout/stderr output and an input field for sending commands.

**Behaviour:**

- Output is streamed via WebSocket — master proxies the agent's Docker attach stream
- The last 2000 lines are buffered and replayed when a user opens the console
- Multiple users can have the console open simultaneously; all receive the same stream
- Commands are sent to the container via RCON (game servers) or stdin (proxies), depending on server type

!!! note
    The console shows raw container output, including itzg startup logs, not just in-game chat and events.
