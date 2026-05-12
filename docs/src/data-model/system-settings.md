# System Settings

## `system_settings`

A key-value store for runtime-configurable operational settings. These are values an administrator may need to adjust post-deployment through the UI — they are distinct from deployment-time configuration (database credentials, secrets, DNS API keys) which lives in the config file or mounted secrets.

| Column | Type | Description |
|---|---|---|
| `key` | VARCHAR(128) | Primary key |
| `value` | TEXT | JSON-encoded for structured values; plain string for simple values |
| `updated_at` | TIMESTAMPTZ | |
| `updated_by` | UUID | FK → `users` — audit trail of who last changed each setting |

### Built-in keys

| Key | Type | Default | Description |
|---|---|---|---|
| `metric_retention_days` | integer | `30` | How many days of node and container metric snapshots to retain |
| `default_backup_max_count` | integer | `10` | Default backup retention limit applied to newly created servers |
| `default_port_range_start` | integer | `25570` | Default start of the host port range for new nodes |
| `default_port_range_end` | integer | `26070` | Default end of the host port range for new nodes |

!!! note "What does not belong here"
    Deployment-time configuration — database connection details, JWT signing keys, DNS provider credentials, TLS certificate paths, bind addresses — is **never** stored in this table. Those values are provided via config file, environment variables, or mounted secrets. See [Configuration & Secrets](../tech-stack/configuration.md).
