# Alerts

## Enums

### `alert_scope`

| Value | Meaning |
|---|---|
| `NODE` | Threshold applies to a node-level metric |
| `SERVER` | Threshold applies to a server-level metric or state |

---

## `alert_thresholds`

Defines a condition that master monitors. When the condition is met, an `alert_events` record is created and a notification is surfaced in the UI.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `scope_type` | `alert_scope` ENUM | `NODE` or `SERVER` |
| `scope_id` | UUID | ID of the node or server this threshold applies to |
| `metric` | VARCHAR(64) | Metric identifier, e.g. `ram_percent`, `cpu_percent`, `disk_percent`, `server_health` |
| `threshold_value` | NUMERIC | Numeric trigger value; `NULL` for state-based alerts |
| `threshold_state` | VARCHAR(32) | State string trigger, e.g. `UNHEALTHY`; `NULL` for numeric alerts |
| `created_at` | TIMESTAMPTZ | |

!!! note
    Exactly one of `threshold_value` or `threshold_state` should be non-null for a given row. Numeric thresholds fire when the metric exceeds the value (e.g. `ram_percent > 90`). State thresholds fire when the metric equals the specified state string.

---

## `alert_events`

A log of fired alert conditions. An event is created when a threshold is crossed and resolved when the metric returns to a normal range.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `threshold_id` | UUID | FK → `alert_thresholds`, CASCADE DELETE |
| `fired_at` | TIMESTAMPTZ | When the threshold was first crossed |
| `resolved_at` | TIMESTAMPTZ | `NULL` if the condition is still active |
| `message` | TEXT | Human-readable description, e.g. `Node node1: RAM at 94%` |
