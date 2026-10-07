## [1.1.1] - 2026-10-07

### Features

- allow resource, placement and expiry overrides when cloning (635857e49844d6d)
- shard Playwright suite and merge shard coverage (a6840874f9f14d2)
- player-aware restart confirmation and copyable ID (246d4632c2e9630)
- surface add failures and suggest channels, sort list (d874538f6b1b14b)

### Bug Fixes

- self-host fonts to drop the next/font/google dev-time fetch (83a44c626d7b336)
- offer start, stop and delete for a crash-looped server (d3deced26b8331e)
- dedupe CodeMirror state/view packages (4c1f1f6a06ed4b3)
- always recreate on restart; auto-push changed specs (508c3b8ae5c8f99)
- return 502 from API proxy when master is unreachable (4a860f9128411e1)

### Performance

- exclude mobile sweep and drop sharding for a single workers job (8b6a7acad597b77)

## [1.1.0] - 2026-10-04

### Features

- suggest pin changes for updateable-but-pinned mods (58d38eb7598950c)
- add per-server scheduled jobs (f6b5bc244a9ab47)
- improve mobile console and files tab UX (a1b6fd412c3acf9)
- detect image updates without restarting running services (93216ab62af60fb)
- make mc-router log level configurable (f5ee411d553088e)
- status history, metrics tab, and MeowIce flags by default (5df5edecfa0f676)
- move DNS config and agent runtime tuning to DB settings (e6c227071da8c73)
- add JVM heap/non-heap metrics per server (#53) (83814fb5e2ffff0)
- isolate game containers from the shared craftpanel network (6ebfa82168ee631)
- collect player count via mc-monitor exec (8569ff701913840)
- user-facing PROXY-protocol toggle for managed proxies (a126fb5b4af3448)
- ownership-aware, bounded DNS exposure with settings preflight (a2fb82e1791f729)
- override reported public IP via NODE_PUBLIC_IP (ed56fb1714a436e)
- allow enabling/disabling individual mods (3294d049a2cb8fa)
- sort server file tree by name and enlarge files UI (efb31db2fc90d7a)

### Bug Fixes

- honor pin strategy in Modrinth compatibility checks (7b0a501be69edc3)
- collapse duplicate "Anonymous Player" entries (ef54574d00c4a67)
- use valid Badge variant for router update indicator (90a6fa73fe3be50)
- tighten mobile tab padding and stack extra-vars rows (09083e457c135a0)
- mobile spacing, safe-area insets, and detail tab wrap (697ce1c02d5066a)
- replay cached WS snapshot to late subscribers (362d13f3a781f70)
- narrow snapshot metrics before the setState closure (bd64d5f69d472f1)
- keep last JVM heap sample between probes (4c170044c553855)
- derive mc-router routing label from ServerHostnames at the single source (5883b27e304ccc3)
- skip JVM probe quietly for images without jattach (f7ea922909d4bd1)
- show player count on the server list (f941660b9aa5120)
- correct Velocity PROXY-protocol path, emit $put not $set (32fc2ab387c9832)
- write velocity forwarding secret to proxy, own PATCH_DEFINITIONS, recreate on env removal (b9bb7190b7e26b7)
- include non-global group assignments in user list (4309d7dbec7d20b)
- canonical hostname, network links, list name weight (4d9acffa00ed88f)
- clear stock forced-hosts in managed proxy patch (1c704a804731953)
- log server errors at WARN/ERROR instead of silently (710cfd6f46df336)
- keep node port bands below the ephemeral range (31aeffe0c912af3)
- base node allocated views on allocatable capacity (14a4e8bcd8fb565)
- keep JVM options active in manual mode (9be06be0389a256)
- update shell app name live after settings save (1ba792d58c05e7a)

### Documentation

- game containers leave the shared craftpanel network (cfbb6f9181a1f2f)
- complete configuration reference and ops guides (e1aeade392ea3b8)

### Breaking Changes

- **DNS provider configuration moved to the database.** `DNS_PROVIDER`, `CF_API_TOKEN` and
  `CF_API_TOKEN_FILE` are removed from master's environment. Configure the provider and Cloudflare
  API token in *System Settings* (encrypted at rest). On the first start after upgrading, master
  migrates values still set via the old environment variables into the settings table automatically
  (temporary migration aid — remove the variables afterwards).
- **Agent runtime tuning moved to the database.** `METRICS_POLL_INTERVAL_SECONDS`,
  `METRICS_COLLECTION_CONCURRENCY` and `AGENT_RECONCILE_INTERVAL_SECONDS` are removed from the agent
  environment. They are install-wide *System Settings* now; master pushes them to every agent on
  connect and live when changed. `restart_max_attempts`, `restart_window_seconds` and
  `jvm_metrics_poll_interval_seconds` reach connected agents the same way instead of riding each
  per-server envelope.

## [1.0.2] - 2026-09-23

### Bug Fixes

- accept a Modrinth version number as a pinned version (52fce7dd513380b)
- preserve blank lines between changelog sections (fe6995084ecd60b)

## [1.0.1] - 2026-09-23

### Features

- folder-browser picker for file move/copy/upload (bb35655b4bf0d86)

## [1.0.0] - 2026-09-22

### Initial Release

First public release of CraftPanel.
