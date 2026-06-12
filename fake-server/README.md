# craftpanel-fake-server

A minimal Kotlin process that mimics the surface area of `itzg/minecraft-server` and
`itzg/mc-proxy` that the CraftPanel agent actually talks to. Used exclusively in system tests.

## What it implements

| Protocol | Port | Purpose |
|---|---|---|
| Minecraft TCP status ping (1.7+) | 25565 | Health check target for mc-monitor; player count + sample list |
| Minecraft UDP query | 25565 | Full player list by name, no cap |
| stdin | — | Receives stop/save commands; logs them for test assertions |

## Health check

The `HEALTHCHECK` in the Dockerfile uses `mc-monitor status` — the exact same mechanism
as `itzg/minecraft-server`. The `mc-monitor` binary is copied from the official
`itzg/mc-monitor` image at build time.

`mc-monitor status` does a TCP status ping against the fake server's `TcpPingServer`.
When it responds, Docker marks the container `healthy`. This means:

- The agent reads health from `docker inspect .State.Health.Status` — no HTTP endpoint needed
- The STARTING → HEALTHY transition in CraftPanel is driven by the container moving from
  `starting` to `healthy` in Docker's health state, exactly as it would with a real server
- There is no HTTP health API to implement or call

**There is no HTTP health endpoint in this image.** If the agent is currently polling an
HTTP endpoint for health, that is a bug — it should be reading `docker inspect` instead.

## TCP vs UDP player list

The TCP status ping `sample` field is capped at 12 player names by the Minecraft protocol.
It is intended for the client server list hover tooltip, not a complete roster.

The UDP query protocol returns the full player list with no cap.

For test purposes both are fine — you won't set more than a handful of `ONLINE_PLAYERS`.
The agent should prefer UDP query when it needs a complete list.

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `GAME_PORT` | `25565` | TCP + UDP game port |
| `SERVER_NAME` | `CraftPanel Fake Server` | Name in status responses |
| `MOTD` | `A fake Minecraft server` | MOTD in ping and query responses |
| `MAX_PLAYERS` | `20` | Reported max player count |
| `ONLINE_PLAYERS` | _(empty)_ | Comma-separated fake online player names e.g. `Notch,jeb_` |
| `STOP_COMMAND` | `stop` | Command that triggers clean shutdown. Override via build arg `STOP_COMMAND=end` for proxy image. |

## Images

Two images are built from the same jar and single `Dockerfile`, differing only in build args:

- `craftpanel-fake-server:test` — game server (default build args)
- `craftpanel-fake-proxy:test` — proxy server (`--build-arg SERVER_NAME="..." MOTD="..." MAX_PLAYERS=100 STOP_COMMAND=end`)

## Agent image override

Set these env vars on the agent container in `CraftPanelStack.kt`:

```kotlin
.withEnv("CRAFTPANEL_IMAGE_OVERRIDE_MINECRAFT", "craftpanel-fake-server:test")
.withEnv("CRAFTPANEL_IMAGE_OVERRIDE_PROXY",     "craftpanel-fake-proxy:test")
```

## Asserting health state in tests

Read from Docker inspect — do not poll HTTP:

```kotlin
val info   = dockerClient.inspectContainerCmd(containerId).exec()
val health = info.state.health?.status  // "healthy", "unhealthy", "starting"
```

Testcontainers also exposes a wait strategy that uses the Docker health state directly:

```kotlin
WaitStrategy = Wait.forHealthcheck()
```

## Asserting stop command delivery

Every stdin line is logged:

```
[fake-server] stdin received: stop
[fake-server] stop command received — shutting down cleanly
```

In Testcontainers:

```kotlin
assertThat(container.logs).contains("[fake-server] stdin received: stop")
```

`save-all`, `save-off`, and `save-on` are also acknowledged and logged — useful for
asserting the backup and migration sequences.

## Asserting env vars

Use Docker inspect directly:

```kotlin
val info = dockerClient.inspectContainerCmd(containerId).exec()
val env  = info.config.env.associate { it.substringBefore('=') to it.substringAfter('=') }
assertThat(env["MODRINTH_PROJECTS"]).contains("lithium")
```

## Asserting mc-router labels

```kotlin
val info   = dockerClient.inspectContainerCmd(containerId).exec()
val labels = info.config.labels
assertThat(labels["mc-router.hostname"]).isEqualTo("survival.mc.example.com")
```

## Build

Built as part of `./gradlew dockerBuildAll`. The fat jar is produced by
`./gradlew :fake-server:jar` and packaged into both images.
