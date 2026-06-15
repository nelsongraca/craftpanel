# CraftPanel — Architecture Context

Domain-model and architecture terms agreed during design/grilling sessions.
Domain *vocabulary* (Server, Node, Scope, Status, …) lives in
`UBIQUITOUS_LANGUAGE.md`; this file records **architecture decisions and named
modules/seams** so future reviews don't re-litigate them.

## Seams & modules

### Authorization seam (master)
The single place that guards REST handlers: parse the resource id, resolve the
**Server**'s **Scope** (its `networkId`), check the required **Permission Node**,
and short-circuit on failure. Implemented as throw-based suspend helpers on
`ApplicationCall` (`requireServerPermission`, `requirePermission`) that throw
`ServiceException` subtypes mapped to HTTP status by the already-installed
`StatusPages`. Replaces the ~66 copy-pasted guard preludes.
- **Why throw-based, not a Ktor plugin/interceptor:** install-on-route forces
  `route()` nesting around every smiley4 doc-blocked handler — awkward and
  indentation-heavy. The throw helper is one line inside the existing handler,
  leaves doc-blocks untouched, and makes per-endpoint permission trivial (no
  verb→permission map). See plan `master-authorization-seam.md`.

### ServerLookup (master)
The one query that resolves a **Server** id to its **Scope** (`networkId`).
Replaces six duplicated copies (`ServerService.authInfo` + five service-local
`getServerScope`/`ServerScope`). Lives behind the authorization seam; not a
general-purpose service.

### SDK call wrapper (frontend)
Single place that consumes the `{data, error, response}` envelope from the
`@hey-api/openapi-ts` generated client. Lives in `frontend/lib/api.ts`.
- `call<T>(fn)` — awaits the SDK call, throws `ApiError` (has `.status`) on
  non-2xx. Use where a 404 needs special handling or inside `useApiData`.
- `tryCall<T>(fn)` — non-throwing; returns `{ok:true, data} | {ok:false, error, status?}`.
  Use in imperative event handlers where you want to `setError(res.error)`.
- `useApiData(loader, deps, {pollMs?})` — wraps load+setInterval(pollMs) pattern.
  Returns `{data, loading, error, reload}`. Live in `frontend/lib/hooks/useApiData.ts`.
- Does **not** touch auth — Bearer injection and 401→refresh+retry remain in
  `lib/client.ts` interceptors.

### DataServiceProxy domain boundary (master)
`DataServiceProxy` is now the only class that knows proto types.
- `correlate<R>(serverId, build, extract, err)` — private generic that eliminates
  the repeated 5-step pattern (lookupNodeId → reqId → sendAndAwait → extract → check).
- Proxy returns route DTOs (`ListFilesResponse`, `ReadFileResponse`) not proto;
  proto-to-DTO mapping lives inside the proxy.
- Route DTOs moved to `routes/dto/FileDtos.kt` (neutral package) to avoid
  backward dependency.
- `ServerStatus.fromProto(p)` — sole mapping point from proto enum to domain enum;
  throws on `UNSPECIFIED`/`UNRECOGNIZED`. `STOPPING` has no proto source (master
  transient only).
- `MigrationService.triggerAndAwaitStatus` accepts `ServerStatus` (domain), calls
  `ServerStatus.fromProto()` internally when comparing against incoming stream
  updates. No other service touches proto enum constants directly.

### Agent event bus (master)

`ControlServiceImpl` emits two `SharedFlow`s instead of 11 typed flows.

- **`agentEvents: SharedFlow<AgentEvent>`** — control-plane events (status changes,
  rsync, backup, alerts, node connection). Buffer `512`. Consumers use
  `filterIsInstance<AgentEvent.FooEvent>()`.
- **`agentMetricsFlow: SharedFlow<AgentMetricEvent>`** — high-volume sampled
  observations (node metrics, container metrics, player counts). Buffer `1024`.
  Single consumer: `DashboardWsRoutes`.
- Both flows and all event types live in `domain/AgentEvent.kt` and
  `domain/AgentMetricEvent.kt`.
- `ControlServiceImpl` is the sole producer: converts proto → domain event before
  emitting. No consumer imports `io.craftpanel.proto.*` for event handling.
- `AlertEventNotification` deleted — fields inlined into `AgentEvent.AlertFiredEvent`.
- `NodeConnectionStatus` enum (`ACTIVE`, `DEGRADED`) defined in `domain/AgentEvent.kt`
  — only two runtime transitions flow through the bus; DB node states (`PENDING`,
  `REJECTED`, `DECOMMISSIONED`) are admin states not emitted as events.
- `MigrationService` constructor drops 4 flow args → 1 (`agentEvents`). Filters
  `RsyncReadyEvent`/`RsyncProgressEvent`/`RsyncCompleteEvent`/`ServerStatusEvent`
  inline.
- See plan `plans/c1-agent-event-bus.md`.

### ContainerLifecycle (master)

`ContainerLifecycle` replaces `ServerLifecycle`. Single class owning the full
container command sequence with status-gated awaiting.

- Public compound ops: `start(server, pull, hostname?)` and `recreate(server, hostname?)`.
- Public primitives (used by `MigrationService` for cross-node relocation):
  `stop(server, nodeId)`, `remove(server, nodeId)`, `create(server, nodeId)`,
  `sendStart(server, nodeId)` — each sends the gRPC command then suspends until
  the agent emits the expected `AgentEvent.ServerStatusEvent` or throws
  `ContainerLifecycleException` on UNHEALTHY or timeout.
- Per-step timeouts on constructor: `createTimeout=10m`, `stopTimeout=45s`,
  `startTimeout=30s`, `removeTimeout=10s`.
- `awaitStatus` uses `coroutineScope {}` — no stored scope.
- `PullImageCommand` deleted from proto — `createContainer` on agent already
  pulls the image atomically; separate pull command was redundant.
- Depends on C1 (`agentEvents: SharedFlow<AgentEvent>`).
- See plan `plans/c2-container-lifecycle.md`.

## Open / planned

### Server lifecycle orchestrator (master) — superseded by ContainerLifecycle

Resolved: `ContainerLifecycle` is the orchestrator. Owns container command
sequencing with step-gated awaiting. See `plans/c2-container-lifecycle.md`.
