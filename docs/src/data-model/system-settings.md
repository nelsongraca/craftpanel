# System Settings

## `system_settings`

A key-value store for runtime-configurable operational settings. These are values an administrator may need to adjust post-deployment through the UI — they are distinct from deployment-time
configuration (database credentials, secrets, DNS API keys) which lives in the config file or mounted secrets.

| Column       | Type         | Description                                                        |
|--------------|--------------|--------------------------------------------------------------------|
| `key`        | VARCHAR(128) | Primary key                                                        |
| `value`      | TEXT         | JSON-encoded for structured values; plain string for simple values |
| `updated_at` | TIMESTAMPTZ  |                                                                    |
| `updated_by` | UUID         | FK → `users` — audit trail of who last changed each setting        |

### Built-in keys

| Key                        | Type    | Default | Description                                                     |
|----------------------------|---------|---------|-----------------------------------------------------------------|
| `metric_retention_days`    | integer | `30`    | How many days of node and container metric snapshots to retain  |
| `default_backup_max_count` | integer | `10`    | Default backup retention limit applied to newly created servers |
| `default_port_range_start` | integer | `25570` | Default start of the host port range for new nodes              |
| `default_port_range_end`   | integer | `26070` | Default end of the host port range for new nodes                |
| `jvm_metrics_poll_interval_seconds` | integer | `30` | How often the agent samples each running server's JVM heap (pushed to agents live) |
| `metrics_poll_interval_seconds` | integer | `5` | Agent node/container metrics polling cadence (s; pushed to agents live) |
| `metrics_collection_concurrency` | integer | `8` | Max server containers an agent samples in parallel per tick (pushed to agents live) |
| `agent_reconcile_interval_seconds` | integer | `30` | Agent convergence backstop sweep cadence (s); `0` disables the sweep (pushed to agents live) |
| `dns_provider`             | string  | `none`  | DNS provider: `none` or `cloudflare`                            |
| `cf_api_token`             | string  | —       | Cloudflare API token — **write-only**, stored encrypted at rest; the API exposes only a `cf_api_token_set` boolean |

!!! note "Pushed to agents"
    The five agent-facing keys (`jvm_metrics_poll_interval_seconds`, `metrics_poll_interval_seconds`,
    `metrics_collection_concurrency`, `agent_reconcile_interval_seconds`, plus the
    `restart_max_attempts`/`restart_window_seconds` pair) are pushed to every connected agent on
    connect and live whenever a value changes — no agent restart required.

!!! note "What does not belong here"
Deployment-time configuration — database connection details, JWT signing keys, TLS certificate paths, bind addresses — is **never** stored in this table. Those values are
provided via config file, environment variables, or mounted secrets. The `cf_api_token` is the one secret stored here, encrypted at rest with `FORWARDING_KEY` — it is not deployment-critical, so losing it only means re-entering the token. See [Configuration & Secrets](../tech-stack/configuration.md).
