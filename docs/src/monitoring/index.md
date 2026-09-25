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

The agent probes each running container for its player list by running the image's bundled `mc-monitor` inside the container:

```bash
docker exec <container> mc-monitor status --json --host localhost --port <internal listen port>
```

The probe is network-independent — it runs inside the container's own namespace — so it works for exposed and non-exposed servers alike, and never depends on mc-router or the Docker network
layout. For a proxy whose listener expects the HAProxy PROXY protocol, the agent adds `--use-proxy` (from the server's PROXY Protocol setting).

Player count and online player list are refreshed every metrics poll (`METRICS_POLL_INTERVAL_SECONDS`, default 5 s) and surfaced on the server detail page and dashboard.

!!! note
`mc-monitor` is bundled with the `itzg/minecraft-server` and `itzg/mc-proxy` images. A custom image
that does not ship it simply reports no player count.

!!! note
`ENABLE_QUERY` is no longer automatically injected into server containers. If you require the UDP query protocol for external tooling (e.g. server list websites), add `ENABLE_QUERY=TRUE` and `QUERY_PORT=25565` via the server's environment variable editor.

## JVM Heap

For running JVM servers the agent samples the heap from inside the container using `jattach` — the utility the `itzg` images bundle (it speaks the JVM dynamic-attach mechanism over a UNIX socket, so it needs no network port and only a JRE, not a JDK):

```bash
docker exec <container> sh -c 'P=$(pgrep -o java); jattach "$P" jcmd GC.heap_info; jattach "$P" printflag MaxHeapSize'
```

The agent reports heap used, heap max (the effective `-Xmx`), and non-heap (metaspace) usage alongside the Docker Stats figures, so you can see how much of a server's RAM is really JVM heap.

JVM sampling runs on its own, slower interval (`jvm_metrics_poll_interval_seconds`, a global system setting, default 30 s) than the container poll, because it costs an extra `docker exec` and can briefly safepoint the JVM. It can be turned off per server with the **JVM Metrics** toggle on the server's General tab. Changes take effect on the next envelope — no restart or recreate.

!!! note
JMX is **not** used. Enabling it would require exposing and reaching a JMX port inside the (network-isolated) game container. `jattach` avoids that entirely. A non-HotSpot runtime or an image without `jattach` simply reports no JVM metrics.

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
