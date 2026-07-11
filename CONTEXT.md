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
  non-2xx. Use where a 404 needs special handling.
- `tryCall<T>(fn)` — non-throwing; returns `{ok:true, data} | {ok:false, error, status?}`.
  Use in imperative event handlers where you want to `setError(res.error)`.
- Does **not** touch auth — Bearer injection and 401→refresh+retry remain in
  `lib/client.ts` interceptors.

### ResourceList lifecycle hook (frontend)
The one place that owns the list-page fetch/poll/reload lifecycle, replacing the
byte-identical `data + initialLoad + reloadX cb + useEffect{cancelled-flag +
setInterval(30s) + cleanup}` dance duplicated across all six list pages
(`nodes`, `servers`, `alerts`, `users`, `groups`, `networks`). Lives in
`frontend/lib/hooks/useResourceList.ts`.
- `useResourceList<T>(loader, {pollMs?})` — `loader` is a bare `@hey-api` SDK fn
  (`listServers`, `listNodes`, …); the hook swallows the `{data}` envelope
  (`if (data) setData(data)`, load errors dropped silently — unchanged behaviour).
  Returns `{data, initialLoad, reload, setData}`. `pollMs` defaults to `30_000`.
- **Single-resource, polled only.** Secondary one-shot loads (nodes+networks on
  servers, server-counts on nodes) stay as plain `useEffect` in the page — a
  genuinely different shape (no poll, no `initialLoad` gating).
- `setData` is the **WS patch seam** — nodes/servers/alerts apply
  `subscribe("node.status", …)` deltas through it, so the hook stays the sole
  owner of the list state.
- Composes with `useAction` (existing) — the hook does **not** own the per-row
  `pendingAction` / `actionError` machinery; mutations stay page-side.
- Note: an earlier `useApiData` seam was documented here but never built; this
  hook supersedes that intent with a narrower, single-resource interface.
- See candidate 4, `improve-codebase-architecture` review 2026-07-05.

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
- `lookupNodeId()` and `lookupServer()` now use `ServerRepository.findById()`
  instead of querying the `Servers` table directly — concentrates DB access
  in the repository layer where it belongs.

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

### ServerExposure (master)

The one module that answers "what is a server's hostname?" — managed hostname,
mc-router label, canonical hostname, network→DNS resolution, and custom-hostname
validation. Replaces `buildMcRouterLabel` (pkg fn), the private resolvers in
`ServerExposureService`, and the 4th DNS-resolve copy in `MigrationContext`.
`ServerExposureService` keeps only the DNS-mutation + restart orchestration of
`updateExposure`; all resolution/validation delegates here.

- `resolveNetworkDns(networkId)` — network → `NetworkDns(zoneId, domainSuffix)`,
  null if the network has no DNS zone. Collapses three separate copies of the
  suffix-resolution logic (`buildMcRouterLabel`, `resolvePublicHostname`,
  `resolveNetworkDns`) into one `resolveSuffix`.
- `resolveSuffix(networkId)` — network's `cfDomainSuffix`, falling back to the
  global `dns_domain_suffix` setting.
- `managedHostname(row)` — `dnsRecordName` if present, else
  `subdomain.resolveSuffix(networkId)` when exposed with a subdomain, else null.
- `mcRouterLabel(row)` — managed + custom hostnames comma-joined, or null.
- `canonicalHostname(row)` — custom hostname takes precedence over managed.
- `validateCustomHostname(hostname, excludeServerId)` — RFC-1123 validation +
  collision checks against other servers' custom/managed hostnames and against
  panel-managed domain suffixes.
- `ServerLifecycleService` and the `UpdateDnsStep` migration step call in via
  an injected `ServerExposure` instead of holding `networkRepository`/
  `settingsRepository` directly.
- `ServerRow.toResponse` (in `ServerService.kt`) now routes through
  `ServerExposure.canonicalHostname(row)` — single source of truth for hostname
  resolution. The previous inline derivation (which lacked suffix fallback) was
  removed to improve locality.
- See plan `plans/c1-server-exposure-module.md`.

### Route test scaffolding (master) — planned

`testApp(routing: Routing.(jwtManager: JwtManager) -> Unit)` — a
`ApplicationTestBuilder` extension replacing the per-file `configureTest()`
hand-rolled in all 12 `routes/*RoutesTest.kt` files.

- Installs `ContentNegotiation`, `StatusPages` (fixed set of 7 exception
  mappers — `NotFoundException`, `ForbiddenException`, `ConflictException`,
  `UnprocessableException`, `BadGatewayException`, `BadRequestException`,
  `ContainerLifecycleException` — always all 7, unused ones are inert),
  and JWT `Authentication` (fixed test secret/issuer/audience) internally.
  Confirmed byte-identical for 6 of 7 mappers + all of `ContentNegotiation`
  + `Authentication` across all 12 files before extracting — genuine
  copy-paste, not independent evolution. Mirrors the exception→status
  mapping in `Main.kt` (tests had silently re-derived it with a raw
  `mapOf` instead of `ErrorResponse`).
- Caller supplies only the `routing { fooRoutes(...) }` block — service
  construction stays visible at the call site, `testApp` stays generic
  infrastructure with no knowledge of the 12 domain route modules.
- `jwtManager` is handed back into the block (not constructed by the
  caller) so tests can still `jwtManager.generate(TokenClaims(...))` to
  mint auth tokens.
- `jsonClient()` (the `ApplicationTestBuilder.createClient { ... }` helper,
  also byte-identical across all 12 files) moves into the same seam.
- Rejected: a `testApp(gateway, vararg services)` auto-wiring variant —
  couples the builder to all 12 domains' service graphs, contradicts
  "generic infrastructure only." Rejected a Kotest `ProjectConfig`-level
  auto-install — too much magic, a reader can't see what's installed
  without checking the extension.
- See candidate 4, `improve-codebase-architecture` review 2026-07-01.

### MigrationPlan + MigrationCoordinator (master)

Splits the god-struct `MigrationContext` (21 ctor fields, 5 mutable vars, 8
behavior methods, 6 live collaborators — leaked repos/gateway/DNS/lifecycle
across all 12 `migration/steps/`) into two deep modules. Step signature becomes
`execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult`.

- **`MigrationPlan`** — pure state, NO behavior, NO collaborators. Immutable
  per-migration facts (`migrationId`, `serverId`, source/target node ids +
  rows, `rsyncImage`, `playerWarningMessage`, `containerNamePrefix`) plus the 5
  mutable cross-step data vars (`rsyncPort`, `rsyncPassword`, `sourceStopped`,
  `assignedPort`, `freshServerRow`). Mutable-state-object flow kept — step N
  writes, step N+k reads (e.g. `rsyncPort` written by `AllocateRsyncPortStep`,
  read by `PrepareRsyncReceiveStep`/`FinalRsyncStep`/`UpdateNodeAssignmentStep`).
  Rejected StepResult-carries-deltas: rewrites all 12 step signatures for no
  behaviour gain.
- **`MigrationCoordinator`** — the seam steps call. Owns ALL collaborators
  (`serverRepository`, `nodeRepository`, `gateway`, `dnsProvider`, `lifecycle`,
  `serverExposure`, `scope`, `eventFlow`) + all behavior (`emit`, `updateStatus`,
  `startStep`, `completeStep`, `failMigration`, `restartSource(plan)`,
  `allocateRsyncPort(plan)`, `updateProxyBackendsAfterMigration`,
  `resolveTargetDns(plan)`). The `MigrationRunner` finally-block cleanup
  (rsync-recv `removeContainer` + `releasePort`) moves here too.
- **DNS folded in.** `UpdateDnsStep` currently takes `serverExposure` via
  constructor while other collaborator-using steps reach through the struct —
  that inconsistency is resolved by folding `serverExposure.resolveNetworkDns`
  behind `coord.resolveTargetDns(plan)`. ALL collaborators sit behind one seam;
  no step takes a constructor collaborator arg.
- **Testability:** a step is exercised with a fake `MigrationCoordinator` + a
  plain `MigrationPlan` — no live `DnsProvider`, repo, or gateway. The interface
  is the test surface. Deletion test passes both ways: drop the Coordinator and
  collaborators+behavior scatter across 12 steps; drop the Plan and cross-step
  data has nowhere to live.
- `MigrationRunner(steps, plan, coord)`; `MigrationService.runMigration`
  constructs both and passes them in.
- **Collaborator exposure (as built):** coordinator holds behavior as methods
  (`allocateRsyncPort`, `updateProxyBackendsAfterMigration`, `resolveTargetDns`,
  `restartSource`, step-log/status) but exposes plain collaborators as **public
  vals** (`serverRepository`, `gateway`, `lifecycle`, `scope`, `dnsProvider`) for
  steps that call straight through — the deepening is getting collaborators OUT
  of the shared-state struct and behind ONE seam, not wrapping every call.
  `serverExposure` + `eventFlow` stay `private` (only reached via
  `resolveTargetDns`/`emit`). `MigrationCoordinator` is `open` so tests
  subclass-and-override a single method (proven in `AllocateRsyncPortStepTest`,
  `MigrationRunnerTest`).
- See candidate 1, `improve-codebase-architecture` review 2026-07-05.

### NodeRepository seam — ControlServiceImpl (master) — Tier A of candidate 2

`ControlServiceImpl` no longer opens `transaction { Nodes.* }`; all four raw
sites (`registerNode`, `identifyNode`, `control()` status read, `verifyNodeKey`)
route through the injected `NodeRepository`. The gRPC transport stops owning
schema — registration/identify/verify are now testable through a fake repo with
no live DB (see the three FakeNodeRepository-backed tests in
`ControlServiceImplTest`).

- **`create()` widened**, not duplicated: gained `totalRamMb`, `totalCpuShares`,
  `agentVersion`, `lastSeenAt` (all defaulted so existing callers are untouched).
- **`updateStatus(id, NodeStatus)`** (was `String`) — folds the `NodeStatus`
  enum (`toDb()`) in at the seam. `identifyNode` maps via
  `NodeStatus.fromDb(row.status)` instead of stringly `when`. Callers
  `NodeService` `ACTIVE`/`REJECTED` converted.
- **`updateLastSeen` gained `privateIp: String? = null`** to preserve
  `identifyNode`'s inline privateIp write under the shared null-means-skip
  contract.
- **Behavior note (intentional):** `identifyNode` previously wrote
  `agentVersion` unconditionally (empty string → cleared stored version).
  `updateLastSeen`'s null-means-skip means an empty agentVersion no longer
  clobbers a stored value — the shared method's contract (5+ metric callers) is
  kept; the old clobber-on-empty was incidental.
- **portRange:** `registerNode`'s inline insert never set port range (DB column
  default). `ControlServiceImpl` now passes explicit `DEFAULT_PORT_RANGE_START/
  END` constants mirroring the `Nodes` schema defaults; real range assignment
  still happens at admin approval via `NodeService.updateNode`, unchanged.
- See candidate 2, `improve-codebase-architecture` review 2026-07-05.

**Tier B (route/scheduler leaks → existing repos):**
- `AuthRoutes` `lookupUser`/`lookupUserById` → `UserRepository` (new
  `findCredentials(email): CredentialRow` for the passwordHash `UserRow` doesn't
  expose; `findById` + `getUserGlobalGroups` reused). Route takes a
  `UserRepository` param.
- `ServerScheduler` backup half → existing `listWithBackupSchedule` +
  `updateBackupScheduleLastFired`; generic-job half → NEW
  `ServerRepository.listEnabledServerJobs()` + `updateServerJobLastFired()` +
  `ServerJobRow` (ServerJobs is server-scoped → lives on ServerRepository, not a
  separate JobRepository). **Removed dead duplicate `ServerJobRow`/
  `findJobsByType`/`updateJobLastFired`/`findEnabledJobs` from
  `SettingsRepository`** — confirmed 0 external callers; consolidates ServerJobs
  ownership on ServerRepository.
- `DashboardWsRoutes` `serverNetworkId` → `findById(id)?.networkId`; snapshot →
  `ServerRepository.listAll()` + `getLatestContainerMetricsForServers` +
  `NodeRepository.listAll()`. Chose **typed rows out, DTOs assembled in the
  route** — `ServerSnapshot`/`NodeSnapshot` are `@Serializable` WS-wire types, so
  repo-built DTOs would invert the dependency (repo → API contract). Permission
  filtering stays in the route (needs `PermissionResolver`). Note: snapshot is no
  longer one atomic `transaction{}` (each repo call opens its own) — acceptable
  for a best-effort dashboard read.

**Tier C — `RefreshTokenService` → `UserRepository`:**
- issue/rotate/revoke/revokeAll now call the existing `UserRepository` token
  methods (`issueRefreshToken`/`findRefreshTokenByHash`/`rotateRefreshToken`/
  `revokeRefreshToken`/`revokeAllRefreshTokens`/`isActive`).
- **Gap preserved:** `findRefreshTokenByHash` has no revoked/expiry filter (the
  old inline query did) — the service replicates the `revoked ||
  expiresAt <= now` gate on the returned row before proceeding.
- `revokeAllRefreshTokens` confirmed soft-delete (`UPDATE SET revoked=true`),
  matching the CLAUDE.md constraint. `rotateRefreshToken` confirmed atomic.
- `RefreshTokenService` is NOT a pure pass-through (`rotate` keeps the
  expiry/revoked/isActive gate + token generation) — kept, not deleted.

`PermissionResolver` + `ServerLookup` remain deliberate seams (not touched).

### AlertEvaluator (master)

The one module that decides "did a metric cross its threshold, and should an
alert event open or resolve?" Extracted from two near-identical ~55-line
private methods (`evaluateNodeAlerts`/`evaluateServerAlerts`) trapped inside
`NodeObserver`'s flow subscriber.

- `evaluate(scopeType, scopeId, scopeLabel, metricValues): List<AlertFiredEvent>`
  — side effects limited to `AlertRepository` writes; caller emits the returned
  notifications (keeps the evaluator off the event bus).
- **Caller builds the metric snapshot.** The two `buildMap` blocks are genuinely
  different shapes (node: totals from the event; server: `memoryMb` lookup via
  `ServerRepository`) — they stay in `NodeObserver`, which keeps the evaluator
  scope-agnostic and free of a second repo dependency.
- `scopeLabel` ("Node <id>" / "Server <id>") keeps message text caller-owned.
- Clock injected (`kotlin.time.Clock`), matching the `NodeObserver` pattern.
- Rejected: folding evaluation into `AlertService` (route-facing CRUD service;
  `NodeObserver` shouldn't depend on it). Rejected: evaluator consuming raw
  events + repos (couples it to event types for no depth gain).
- Tested via `FakeAlertRepository` + fixed clock (`AlertEvaluatorTest`) —
  previously untestable without spinning the event bus.
- See candidate 1, `improve-codebase-architecture` review 2026-07-11.

### ContainerManager deepening (agent)

`ContainerManager` absorbs two operations previously scattered across handler
constructors:

- **`isSwarmActive(): Boolean`** — wraps `docker.infoCmd()` with `runCatching`,
  returns `false` on any exception. Used by `ControlStreamHandler` for
  `buildStateSnapshot()`.
- **`attachInteractive(containerName, inputStream, callback): ResultCallback<Frame>`**
  — wraps `attachContainerCmd` with `withLogs(false)` (live interactive session,
  no log replay). Distinct from `sendStopCommandToStdin` which uses
  `withLogs(true)`.

`ControlStreamHandler` no longer takes `DockerClient` directly. The `docker`
field is constructed by `ConnectionManager` and passed to `ContainerEventWatcher`
and `RsyncMigrator` (both need daemon-level Docker access not in
`ContainerManager`'s surface). `ConsoleHandler` no longer takes `DockerClient`
— its `handleConsoleAttach` calls `containerManager.attachInteractive()`.

`RsyncMigrator` stays independent: its `createContainer` is semantically
incompatible (ephemeral utility containers vs. managed game servers),
`logContainerCmd` streaming is migration-specific, and only `startContainer`/`removeContainer`
are trivially reusable. `MigrationHandler` already uses `ContainerManager`
for `pullImage` — the right seam.

`MetricsCollector`, `NetworkManager`, `McRouterProvisioner`, and
`ContainerEventWatcher` each own their Docker concerns and are not
handler-dispatch candidates.

See candidate 4, `improve-codebase-architecture` review 2026-07-11.

## Open / planned

### Server lifecycle orchestrator (master) — superseded by ContainerLifecycle

Resolved: `ContainerLifecycle` is the orchestrator. Owns container command
sequencing with step-gated awaiting. See `plans/c2-container-lifecycle.md`.
