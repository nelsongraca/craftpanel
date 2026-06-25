# Monitoring

## Server Liveness

The `itzg/minecraft-server` image exposes a built-in health check endpoint. Master polls this via the node agent and displays a health status badge on every server card and detail page.

| Status        | Meaning                                                         |
|---------------|-----------------------------------------------------------------|
| **Starting**  | Container is running; itzg is still downloading or initialising |
| **Healthy**   | Server is accepting connections                                 |
| **Unhealthy** | Container is running but health check is failing                |
| **Stopped**   | Container is not running                                        |

## Player Count

The agent retrieves player count using the **Minecraft TCP status ping** protocol, routed through the local mc-router on port 25565. mc-router proxies the status request to the target game container.

Player count and online player list are refreshed every 60 seconds and surfaced on the server detail page and dashboard.

!!! note
`ENABLE_QUERY` is no longer automatically injected into server containers. If you require the UDP query protocol for external tooling (e.g. server list websites), add `ENABLE_QUERY=TRUE` and `QUERY_PORT=25565` via the server's environment variable editor.

## Node Metrics Dashboard

The master UI presents a per-node dashboard with current and historical values for:

- CPU utilisation
- RAM usage
- Network I/O (in/out)
- Disk usage

Charts display the last 24 hours by default, with a configurable range up to the data retention limit (default 30 days).

## Per-Server Resource Usage

Container-level CPU, RAM, and network I/O — sourced from the Docker Stats API via the agent — are shown on each server's detail page alongside the player count and health status.

## Alerting

Basic threshold alerts are configurable per node or per server. When a threshold is crossed (e.g. node RAM > 90%, server health becomes Unhealthy), master logs an alert event and surfaces a
notification in the UI.

Email and webhook delivery for alerts are planned future enhancements.
