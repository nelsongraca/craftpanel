## [Unreleased]

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
