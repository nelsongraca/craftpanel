# Declarative desired-state control with agent-owned crash restart

## Status

accepted

## Context

Master previously drove every container transition with one-shot commands
(`StartContainerCommand`/`StopContainerCommand`/`RestartContainerCommand`) and owned crash
restart: the agent reported a `die` event, master decided whether to restart, bounded by an
in-memory `ServerRestartManager`, and flipped `servers.status` through `STARTING`/`STOPPING`.
Two problems fell out of this:

- **The `STOPPING` wedge.** Master wrote `STOPPING`, sent a stop, and waited for the agent to
  report `STOPPED`. If that report never arrived (agent restarted, timeout, dropped event), the
  row was stranded in `STOPPING` and subsequent API calls 409'd. This caused recurring CI flakes.
- **Crash restart was chatty and racy.** Several status events describe one death (die watcher,
  console teardown, state snapshot); master de-duplicated them with in-memory sets, and the
  counter lived only in master's heap.

## Decision

Move to a **declarative desired-state** model. Master states intent plus the full runtime spec
and a restart budget; the agent converges and owns container mechanics, including crash restart.

- `servers.desired_status` (`RUNNING`/`STOPPED`, nullable) is master's intent. `servers.status`
  is **agent-reported only**; `STARTING`/`STOPPING` are synthesized at read time by
  `synthesizeStatus(desired, reported)` and never persisted — the wedge class disappears.
- Master sends a `ServerDesiredState` envelope `{desired, spec = StartContainerCommand,
  restart_budget, force_restart?, force?, no_restart?}`. It is idempotent; re-sending an
  already-satisfied envelope is a no-op.
- The agent keeps a per-server **in-memory** desired-state store, a pure `ConvergenceMachine`
  (next-state function, table-testable), and a `ConvergenceLoop` that serializes per-server work
  behind a mutex. Crash restart happens in the agent, bounded by the budget shipped in the
  envelope; exhaustion reports `CRASH_LOOPED`.
- **`needs_recreate` is retired.** The agent decides recreate by comparing the pushed spec to the
  spec the container was last applied with (`spec != appliedSpec`), or container-absent. A spec
  change while running is stored but does not touch the container — it applies at the next
  start/restart.
- The restart budget (`restart_max_attempts`, `restart_window_seconds`) is read from system
  settings and included in every envelope, so a settings change takes effect immediately without
  a master restart.
- The agent store is in-memory; master re-pushes all envelopes on agent reconnect and on master
  boot (`DesiredStateSyncService`), so nothing needs to be persisted on the agent.
- During live migration master guards the source with `no_restart=true` so a crash mid-rsync
  cannot bring the fingerprinted container back and mutate the data set.

Rejected alternatives:
- **Keep master-owned restart, just fix the wedge** (e.g. unconditionally write `STOPPED` on the
  report). Papering over the symptom leaves the command/event de-duplication race and does not
  simplify the surface.
- **Docker restart policy** — cannot distinguish a graceful self-exit from a crash, and cannot be
  budget-capped per server.
- **Persist desired state on the agent** — master already re-pushes on reconnect and boot; a disk
  file buys durability we don't need and adds versioning concerns.

## Consequences

- Status-related API/DB behavior changes: the DB no longer holds transient transitions, and the
  API surfaces a synthesized status. `CRASH_LOOPED` is a first-class status (red in the UI).
- Legacy one-shot proto messages and their agent handlers are deleted; `StartContainerCommand`
  remains only as the spec carrier inside `ServerDesiredState`.
- Crash-restart correctness now depends on the agent's `die` watcher and the watcher gate
  suppressing authored deaths (stop/remove/recreate). The gate is exercised by the desired-state
  path.
- Migration sets `no_restart` on the source for the sync window and clears it on completion or
  failure.
