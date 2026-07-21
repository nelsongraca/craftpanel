# Proxy config applied via a master-rendered JSON patch, written by the agent

## Status

accepted

## Context

Proxy servers (VELOCITY, BUNGEECORD, WATERFALL) route players to backend game
servers. The backend list (name + order, per proxy) is stored in `ProxyBackends`
but was never applied to the running proxy container (issue #36) — the proxy
started with an empty backend registry.

The itzg/mc-proxy image owns config generation. On start its entrypoint
(`run-bungeecord.sh`) downloads a default `velocity.toml` / `config.yml` into
`/server` **create-if-missing** (`mc-image-helper mcopy --skip-existing`), and
for Velocity auto-generates a 32-char `forwarding.secret` (create-if-missing).
It then, in order: syncs `/config` → installs defaults → downloads defaults →
`interpolate` (env replacement) → **`mc-image-helper patch`** against
`PATCH_DEFINITIONS`. The patch phase runs **last, every start**, on the
now-existing config file and applies JSON-path `$set`/`$put` operations to
TOML/YAML.

## Decision

Apply proxy config via itzg's **`PATCH_DEFINITIONS`** mechanism, not by rendering
or overwriting the whole config file. Master renders a small JSON **patch
document** (the `[servers]` / `servers:` entries, `try` / `priorities`, forwarding
mode, motd, show-max-players) from data it already owns; the agent writes the
patch file into the proxy's data dir and sets the `PATCH_DEFINITIONS` env var.
The image applies it on every start.

The render (patch content) stays in **master** — the backend addresses are
computed by master (`craftpanel-<backendId>:<internal port>`), so the agent has
no data to add. The agent stays a pure dispatcher: it writes a file it received
(existing `WriteFileRequest` path) and sets an env var. No new proto message.

Rejected alternatives:
- **Full-file render/overwrite** — would make master own the proxy's entire
  default config forever, and clobber the image's `forwarding.secret`, defaults,
  and any user hand-edits. Patch merges instead of replacing.
- **`CFG_*` env replacement** — the backend list is variable-length; env vars map
  poorly onto a list.
- **Agent-side render** — buys nothing (master already has all the data) and
  breaks the pure-dispatcher rule.

## Consequences

- `forwarding.secret` is **image-generated** and left untouched. #36 only sets
  `player-info-forwarding-mode` in the patch. The backend side of modern
  forwarding (pushing that secret + `velocity.enabled` into each backend) is
  deferred to **#44**.
- After a backend/config change, master persists, re-renders the patch, writes
  it, and sets `needs_recreate = true` (matching env-var and mod edits). The
  proxy is not force-restarted; the patch re-applies on next start.
- Backend addresses use internal docker DNS — same-node only. Cross-node routing
  and the migration backend-address push are deferred to **#43**.
- Patch is idempotent (`$put`), so re-applying every start is safe.
