# Monitoring

## Server Liveness

The `itzg/minecraft-server` image exposes a built-in health check endpoint. Master polls this via the node agent and displays a health status badge on every server card and detail page.

| Status | Meaning |
|---|---|
| **Starting** | Container is running; itzg is still downloading or initialising |
| **Healthy** | Server is accepting connections |
| **Unhealthy** | Container is running but health check is failing |
| **Stopped** | Container is not running |

## Player Count

The agent queries each running game server using the **Minecraft server query protocol** (UDP). Player count and online player list are refreshed every 30 seconds and surfaced on the server detail page and dashboard.

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

Basic threshold alerts are configurable per node or per server. When a threshold is crossed (e.g. node RAM > 90%, server health becomes Unhealthy), master logs an alert event and surfaces a notification in the UI.

Email and webhook delivery for alerts are planned future enhancements.
