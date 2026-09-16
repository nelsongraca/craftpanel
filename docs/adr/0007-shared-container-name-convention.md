# Container name convention is single-owned in a shared `:common` module

## Status

accepted

## Context

Master and the agent both construct Docker names. Master builds a container name, a shared network
name and a standalone network name when it emits a `StartContainerCommand`; the agent re-derives a
container name from a server id (`execRconCommand`, `ConvergenceLoop` fallback), parses a server id
back out of a container name (the `WatcherGate` death gate), and recognises managed networks to
clean them up on removal. The rsync utility container names were built independently by
`MigrationRunner` (master) and `RsyncMigrator` (agent).

Those constructions were copies of one convention, and they drifted:

- `ContainerHandler` filtered a container's networks with the literal prefixes `"craftpanel-net-"` /
  `"craftpanel-server-"`. Under a custom `CRAFTPANEL_CONTAINER_PREFIX` the filter never matched, so
  the shared network was never detached or deleted on server removal — a real leak.
- `BackupService` hardcoded `"craftpanel-$serverId"`, so under a custom prefix the backup command
  addressed a container that does not exist.

## Decision

Own the convention in one place: a new Gradle module `:common` (package `io.craftpanel.common`)
with `class ContainerNames(prefix: String)`. Both master and agent depend on it. Only
**prefix-derived, per-server** names live there:

- `container(serverId)` — `$prefix-$serverId`
- `sharedNetwork(networkId)` — `$prefix-net-$networkId`
- `standaloneNetwork(serverId)` — `$prefix-server-$serverId`
- `rsyncReceive(migrationId)` / `rsyncSend(migrationId, final)`
- `serverIdOf(containerName)` — the inverse; **requires** the prefix and throws otherwise, so a
  foreign name can never be silently mapped to a server id and mis-mark the crash gate
- `isManagedContainerName(name)` / `isManagedNetwork(name)`

The prefix is normalised at construction (trimmed, no trailing separator, non-blank). Callers keep
their existing `containerNamePrefix` config value and wrap it once, so construction sites and DI
are unchanged.

Host-global names — the shared `craftpanel` network (`CRAFTPANEL_NETWORK`) and the mc-router
container (`MCROUTER_CONTAINER_NAME`, defaulting to `craftpanel-mc-router`) — are deliberately
**not** prefix-derived: both are one-per-host infrastructure, not per-server.

## Consequences

- The convention cannot drift between the two processes; the class of bug that produced the network
  leak and the backup name is structurally prevented.
- The agent's inverse and predicates are now strict and unit-tested, including under a non-default
  prefix.
- `:common` joins the root aggregate `test` task, the kover aggregation, and the `installDist`
  distributions (master and agent pick it up as a project dependency; Docker packaging copies the
  distribution wholesale, so no Dockerfile change).
- No proto change was needed: `container_name`/`docker_network` are already carried in
  `StartContainerCommand`, and the agent's remaining derivations are prefix-derived names the module
  now owns.

Rejected alternatives:

- **Mirrored `ContainerNames` in each module** — the smallest change, but it re-creates exactly the
  copy that drifted and caused the bug.
- **Put the names in the proto module** — `proto/` is not a Gradle module (only `.proto` files, each
  JVM module extracts them); it is a wire-definition folder, not a place for shared Kotlin.
- **Master sole inventor; agent derives nothing** — the deepest option, but it needs new proto
  fields (`SendRconCommand`/`StartRsyncCommand` container names) and a `ContainerManager` signature
  change, so it is deferred.
