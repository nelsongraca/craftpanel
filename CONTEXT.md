# CraftPanel — Architecture Context

Domain-model and architecture terms agreed during design/grilling sessions.
Domain *vocabulary* (Server, Node, Scope, Status, …) lives in
`UBIQUITOUS_LANGUAGE.md`; this file records **architecture decisions and named
modules/seams** so future reviews don't re-litigate them.

## Seams & modules

### Authorization seam (master)
The single place that guards REST handlers: parse the resource id, resolve the
**Server**'s **Scope** (its `networkId`), check the required **Permission Node**,
and short-circuit on failure. Throw-based helpers on `ApplicationCall`
(`RouteAuthorization.kt`) that throw `ServiceException` subtypes mapped to HTTP
status by `StatusPages` — **every** REST denial goes through them, including the
network-scoped and explicit-id cases:
- `requireServerPermission(permission): AuthorizedServer` — reads `{id}`.
- `requireServerPermission(serverId, permission): AuthorizedServer` — when the id
  comes from elsewhere (e.g. a `migrationId`).
- `requireNetworkPermission(permission): Uuid` — reads `{id}`, 404s on unknown.
- `requireNetworkPermission(networkId, permission)` — id from a body; caller owns existence.
- `requirePermission(permission)` — global.
- **Why throw-based, not a Ktor plugin/interceptor:** install-on-route forces
  `route()` nesting around every smiley4 doc-blocked handler — awkward and
  indentation-heavy. The throw helper is one line inside the existing handler,
  leaves doc-blocks untouched, and makes per-endpoint permission trivial (no
  verb→permission map). See plan `master-authorization-seam.md`.

### GrantIndex (master)
The one scope-union algorithm: a user's group assignments plus their groups'
permission nodes, indexed by scope. Pure and immutable
(`GrantIndex.from(assignments, permissionsByGroup)`), with
`permissionsFor(serverId?, networkId?): Set<String>` and
`scopesGranting(permission): ScopeSet(global, serverIds, networkIds)`.
- Every scope may hold several groups (two SERVER-scoped groups on one server);
  the union is over all of them — the previous `associate { scopeId to groupId }`
  silently kept only the last.
- `PermissionResolver` builds it from schema queries; `ServerVisibilityResolver`
  and `NetworkVisibilityResolver` build it from `UserRepository`/`GroupRepository`
  — one algorithm, the data paths stay as they were.
- `ScopeType.NODE` is **alert-only** (`AlertThresholds.scope_type`); assignments
  accept GLOBAL/SERVER/NETWORK. `AssignmentService` rejects anything else.

### WsAuthorization (master)
The one seam that authorizes a WebSocket connection, since a socket answers a
failed check with a close code (1008), not an HTTP status.
- `authorizeServerSocket(call, permission, serverId? = null): Granted | Denied`
  — consumes `?ticket=`, resolves the server scope (or takes an explicit id, for
  the migration path), checks the permission. `Denied` carries the close code +
  message; the route closes.
- `consumeTicket(call): Uuid?` — for the resource-less dashboard socket.
- `revalidatePeriodically(interval) { check } { onRevoked }` — one 5-minute
  revalidation loop shared by all three sockets; tolerates transient DB errors
  and lets the route run a pre-close action (console's Disconnected event).
- Console's per-input permission re-check was removed — one DB resolution per
  keystroke for no extra safety over revalidation.

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
- Composes with the entity action hooks (below) — the list hook owns list state;
  per-row `pendingAction` / `actionError` and mutations live in
  `useServerActions`/`useNodeActions`, not in the page.
- Note: an earlier `useApiData` seam was documented here but never built; this
  hook supersedes that intent with a narrower, single-resource interface.
- See candidate 4, `improve-codebase-architecture` review 2026-07-05.

### Entity action hooks (frontend)
The one owner per entity of the action policy + execution: `useServerActions`
(`frontend/components/servers/server-actions.tsx`) and `useNodeActions`
(`frontend/components/nodes/node-actions.tsx`). Replace the per-page `ACTION_FNS` /
`doAction` / `doDelete` copies and the status matrices that had drifted between
each entity's list and detail views.

- `allowedServerActions(server, permissions): ServerActionKind[]` — the canonical
  matrix: start `STOPPED && !disabled`; stop `HEALTHY|STARTING|UNHEALTHY`;
  forceStop `STOPPING`; restart `HEALTHY && !disabled`; duplicate `server.create`;
  delete `STOPPED`. `useServerActions({permissions, serverPermissionsMap,
  onChanged, onDeleted?})` returns `{allowedActions(server), run(id, action),
  remove(server), duplicate(server), pendingFor(id), actionError, setActionError, dialog}`.
- `allowedNodeActions(node, serverCount): NodeActionKind[]` — trust/reject
  `PENDING`; rotate `!= PENDING`; shutdown `ACTIVE`; decommission
  `serverCount === 0 && != DECOMMISSIONED`. `useNodeActions({onChanged,
  onTokenRotated, onDecommissioned?})` returns `{allowedActions(node, count),
  trust, reject, rotate, shutdown, decommission, pendingFor(id), actionError,
  setActionError, dialog}`.
- **Policy is shared; rendering is not.** Each context renders the allowed-action
  list with its own button: `ServerActions`/`NodeActions` (icon buttons, lists) vs
  the `HeaderActionButton` maps in the detail headers. The matrix cannot drift.
- **Canonical-matrix fixes over the previous per-page copies:** the server list
  gains stop-on-`UNHEALTHY`; the server detail no longer shows Rotate Key for a
  `PENDING` node; the node list no longer offers Decommission for a
  `DECOMMISSIONED` node.
- `useConfirmDialog` is owned by the hook (the page renders the returned `dialog`);
  `onChanged`/`onDeleted`/`onTokenRotated`/`onDecommissioned` callbacks cover the
  list-reload vs detail-redirect differences.
- Tested at the hook interface (`server-actions.test.tsx`,
  `node-actions.test.tsx`); the two node page specs keep render/integration cases
  only.
- Supersedes the ResourceList note above ("mutations stay page-side").
- See candidate 5, `improve-codebase-architecture` review 2026-09-20.

### ConfigSection lifecycle hook (frontend)
The one owner of a config section's edit lifecycle, replacing the hand-rolled
draft/saved/dirty/save/error state in each section. Lives in
`frontend/lib/hooks/useConfigSection.ts`.

- `useConfigSection<T>({initial, load?, persist})` returns
  `{draft, saved, setDraft, isDirty, loading, saving, error, setError, save, discard}`.
- `load(): Promise<{data?, error?}>` runs on mount and, when present, **after a
  successful save** (so server-normalised values / warnings are re-read).
  Absent `load` ⇒ prop-driven: `draft = saved = initial`, and a successful save
  snapshots the draft.
- `persist(draft): Promise<{data?, error?}>` is the SDK call; an `error.message`
  sets the section error, and `save()` returns the result so a caller can read
  `data` (e.g. `forwarding_warnings`). Validation with no SDK call is expressed by
  returning `{error: {message}}` from `persist` (env-config duplicate keys,
  proxy-backend duplicate names).
- `isDirty` is `JSON.stringify(draft) !== JSON.stringify(saved)`. `load` must be a
  stable reference (memoised) — it is an effect dependency.
- **Callers:** `stop-command-section` (`string`), `proxy-settings-section`
  (`{motd,maxPlayers,forwardingMode}`), `proxy-backends-section`
  (`EditableBackend[]`), and `useServerEnvConfig` (composes it with an
  `{form, extraVars}` draft and the env partition). `config-mode-toggle` is a
  one-shot async action with no draft and is left alone.
- Tested at the hook interface (`useConfigSection.test.ts`); the per-section specs
  stay as integration coverage of each section's `load`/`persist` wiring.
- See candidate 6, `improve-codebase-architecture` review 2026-09-20.

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

### ServerIntent (master)

The one owner of `servers.desired_status` writes and the "record intent → send → revert on
failure" invariant. Extracted from five hand-rolled copies across `ContainerLifecycle`
(`start`/`stop`) and `ServerLifecycleService` (`startServer`/`restartServer`/`requestStop`).

- `record(serverId, desired)` — sole desired_status writer for callers that need no revert
  (`DesiredStateSyncService` reconnect/boot sync).
- `withIntent(serverId, desired, action: suspend () -> Boolean)` — reads the previous intent,
  writes `desired`, runs the action; a `false` return means the agent is not connected and
  throws `BadGatewayException("Agent not connected")`; reverts to the previous intent on ANY
  throw (send-false or await timeout/UNHEALTHY) and rethrows.
- Bookkeeping only — no proto/spec knowledge, and no dependency on `ContainerLifecycle` (which
  calls back into it), so no cycle. `sendDesiredState` stays on `ContainerLifecycle` as the one
  sender; `ContainerLifecycle.persistDesiredStatus` and both private `setDesiredStatus` copies
  are deleted.
- Writes via `transaction { Server.findById(...) }` (ADR-0004: the service owns the transaction;
  no bare-setter repo method).
- Tested at the interface with H2 + a fake action block (`ServerIntentTest`): success / `false`
  (revert to null and to a previous value) / throw.
- See candidate 1, `improve-codebase-architecture` review 2026-09-20.

### ProxyPatchWriter (master)

The one owner of the proxy patch write — filename convention, `ProxyConfigPatchService.generatePatch`,
and the file write. Replaces three private copies (`ServerLifecycleService.writeProxyPatch`,
`ProxyBackendService`/`ProxySettingsService.writePatchIfRunning`).

- `write(server: ServerView)` — ungated, used before start/restart. No-op for non-proxy,
  manual-mode, or no-patch.
- `writeIfRunning(server: ServerView)` — gated on `ServerStatus.HEALTHY`.
- Takes the `ServerView` so it can guard `serverType.isProxy` without a repository round-trip;
  owns the `"craftpanel-patch.json"` literal (`ContainerLifecycle`'s `PATCH_DEFINITIONS` env var
  is a separate container-path concern).
- Tested with a fake patch service + captured `writeFile` (`ProxyPatchWriterTest`).
- See candidate 1, `improve-codebase-architecture` review 2026-09-20.

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

### ProxyConfigRenderer (master) — planned (issue #36)

The one module that turns a **Proxy**'s stored **Backend Servers** into a
**Proxy Config** file. Master renders the full config text; the agent writes it
verbatim via `WriteFileRequest` (ADR-0002). The agent gains no proxy templating.

- Two render targets switched on `server_type`: `velocity.toml` (VELOCITY) vs
  `config.yml` (BUNGEECORD, WATERFALL).
- Backend address resolves to internal docker DNS `craftpanel-<backendId>:25565`
  (same-node only — cross-node addressing deferred to #43).
- Lowest-order backend seeds `try` (Velocity) / `priorities` (Bungee); all
  backends populate `[servers]` / `servers:`.
- Forwarding mode + secret written proxy-side only; backend-side push deferred
  to #44.
- `ProxyBackendService.replaceBackends` is the write trigger: persist backends →
  render + write config → `updateNeedsRecreate(true)`. No forced restart —
  mirrors env-var/mod edits.
- Migration's stale-proxy-config update (`MigrationCoordinator.updateProxyBackends
  AfterMigration`, currently a bare restart) is a cross-node concern → folded
  into #43, not #36.

> NOTE: the paragraph above predates the PATCH_DEFINITIONS decision — #36's final
> design patches the existing config (not full-file render/verbatim write) and
> uses port 25577 for proxies. See `.scratch/proxy-backend-config/DESIGN.md` +
> ADR-0002 for the current mechanism. Update this block when #36 lands.

### BackendForwardingRenderer (master) — planned (issue #44)

The module that writes **Backend Forwarding Config** into each **Backend Server**
so it accepts forwarded players. Follows #36; reuses its patch/`writeFile` path.

- Master mints and owns the **Forwarding Secret** (ADR-0003), encrypted-at-rest,
  key outside the DB — supersedes ADR-0002's image-owned-secret clause.
- Per-backend patch: `paper-global.yml proxies.velocity.*` (modern, Paper-lineage
  only) or `settings.yml bungeecord: true` (legacy, any Bukkit). Always paired
  with `ONLINE_MODE=false` env (the actual "invalid player data" fix) and a new
  `PATCH_DEFINITIONS` env on the backend (backends lacked it before #44).
- Two triggers: proxy-settings mode/secret change, and a new **Backend Server**
  assigned to an already-forwarding **Proxy** (`replaceBackends`) — a 1→N write
  across backend rows. Each affected backend gets **Needs Recreate**.
- Ineligible backends (Vanilla, modded, Spigot under `modern`) are warn-skipped,
  not blocked. Fabric/Forge forwarding deferred (mod-dependent).
- `online-mode=false` backends must stay internal-only — never exposed directly.

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

### NodeRegistrationService (master) — node identity seam

The one owner of node identity: registration, identification, active-node enforcement, and
node-key minting/hashing. Replaces `grpc/NodeRegistrar`, which had regressed to opening raw
`transaction { Nodes.* }` in the transport layer and hand-projecting a 20-field `NodeRow`.

- `register(bootstrapToken, metadata: NodeMetadata): RegisteredNode` — validates the bootstrap
  token, mints a node key, inserts the Node entity (status PENDING), returns `(nodeId, rawKey)`.
- `identify(rawKey, metadata): IdentifiedNode` — hashes the key, refreshes identity fields +
  `lastSeenAt`, returns `(NodeStatus, nodeId?)` (`REJECTED`/null for an unknown key).
- `requireActive(nodeId)` — throws `NodeNotActiveException(reason)` when the node is not ACTIVE.
- `isActive(rawKey): Boolean`; `mintKey()` / `hashKey(raw)` (SHA-256 hex).
- Reads via `NodeRepository`; writes via Exposed entities in its own `transaction {}` (ADR-0004
  #4). `NodeStatus` replaces the stringly `"ACTIVE"`/`"PENDING"` checks.
- **Transport maps at the edge:** `ControlServiceImpl` maps proto ↔ `NodeMetadata`/`RegisteredNode`/
  `IdentifiedNode` and maps `NodeNotActiveException` → `StatusException(PERMISSION_DENIED)`;
  `BulkDataServiceImpl` calls `isActive`. `NodeService.rotateToken` delegates to `mintKey()/hashKey()`.
- Tested at the interface with H2 + `TestDatabase` (`NodeRegistrationServiceTest`).
- **Supersedes** the earlier "NodeRepository seam — ControlServiceImpl" entry, which documented
  `create()`/`updateStatus()`/`updateLastSeen()` write methods on `NodeRepository` — those were
  removed by ADR-0004's write seam (repositories are read-only + behavioural ops). The old
  `NodeRegistrarTest`'s "no live DB" claim was false (it wrote via raw transactions); that test is
  deleted, along with the dead 20-field projection and `FakeNodeRepository.updateStatus`.
- See candidate 2, `improve-codebase-architecture` review 2026-09-20.

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

### Binary file response seam (master)

`ApplicationCall.respondBinaryFlow(flow)` + `ResponseConfig.binaryFileBody()`, both in
`routes/Common.kt`. The one place that answers "how does a route return a downloadable
file?" — replaces the independently-written `Flow<ByteArray>` → `List<Int>` → JSON
`call.respond(byteList)` pattern duplicated in `FilesRoutes`/`downloadServerFile` and
`BackupsRoutes`/`downloadBackup`, which fully buffered the file in memory and shipped it
as a 3-4x inflated JSON integer array under `application/json`.

- `respondBinaryFlow(flow: Flow<ByteArray>)` streams chunks straight to the response
  channel via `respondBytesWriter`, `Content-Type: application/octet-stream`. Never
  materializes the whole file. Callers must verify the file/resource exists **before**
  calling it — `respondBytesWriter` commits HTTP 200 immediately.
- `binaryFileBody()` is the matching OpenAPI doc-block declaration: a `Schema<Any>`
  with `type = "string"; format = "binary"`, `mediaTypes = [OctetStream]`. Declaring
  it this way (vs `body<ByteArray>()`, which schema-generates as a JSON array of
  integers) is what makes the *contract* binary, not just the runtime response.
- The system-tests OpenAPI generator (kotlin/jvm-okhttp4) cannot infer `java.io.File`
  for these two operations specifically, because both also declare typed JSON error
  bodies (`ErrorResponse`) on other status codes — mixed content types across an
  operation's responses defeat the generator's single-type inference. It falls back to
  `kotlin.Any`; callers cast `as ByteArray`. This is a real, verified generator
  limitation (confirmed by regenerating and reading the emitted client), not a
  workaround — a future single-content-type binary operation would get `File` for
  free from the same `binaryFileBody()` doc block.
- See `CLAUDE.md`'s corrected note on the system-test client's binary-body NPE — the
  NPE case is specifically an unscoped `application/octet-stream` alongside typed DTOs
  with no schema discriminator; a clean `format: binary` schema does not hit it.

### DashboardService (master)

Deep service seam for the dashboard WebSocket (`/api/ws`). Absorbs snapshot
construction, permission-gated event filtering, and agent event subscription
so the route is pure transport.

- `getSnapshot(userId)` — fetches all servers + metrics + nodes, applies
  `DashboardEventFilter` permission gates, returns `WsEnvelope`.
- `filteredEvents(userId)` — returns cold `Flow<WsEnvelope>` by collecting
  `AgentGateway.agentEvents` through a per-user `DashboardEventFilter`.
- Takes `AgentGateway`, `ServerRepository`, `NodeRepository`, `PermissionResolver`
  via constructor injection (Koin).
- `PermissionResolver` is registered in Koin as `single { PermissionResolver }`
  (Kotlin object singleton — already a singleton, Koin manages it for injection).
- Route slimmed from 4 params (`WsTicketService`, `SharedFlow<AgentEvent>`,
  `ServerRepository`, `NodeRepository`) to 2 (`WsTicketService`, `DashboardService`).
- Fixes `get<ControlServiceImpl>().agentEvents` coupling in `AppRoutes.kt` —
  the route no longer reaches into the concrete gRPC impl.
- `DashboardEventFilter` stays as the pure-logic layer (no DB/socket access),
  constructed internally by `DashboardService` per userId.
- 5-min permission revalidation stays in the route (WebSocket lifecycle concern).
- `serverNetworkId` per-event `findById` left uncached — tracked in issue #25.
- ADR: `docs/adr/0001-dashboard-service-seam.md`.
- See candidate 2, `improve-codebase-architecture` review 2026-07-11.

### ConsoleSessionManager (master)

The one seam that owns the console viewer refcount lifecycle. Moved out of
`ConsoleRoutes.kt` into its own file (`ConsoleSessionManager.kt`), `internal`
visibility (was a private nested class).

- All map mutation routes through one `private fun mutate(serverId,
  transform: (ConsoleSession?) -> ConsoleSession?)` wrapping
  `ConcurrentHashMap.compute` — `getOrCreate`, `releaseViewer`, and the
  console job's `finally` cleanup (`removeIfCurrent`) each supply their own
  transform instead of hand-rolling three independent `compute` calls.
- Job launch (`scope.launch { ... }`) happens **outside** the `compute`
  lambda — `getOrCreate` tracks a `created` flag from the pure transform,
  then launches after `mutate` returns. Launching a coroutine inside
  `compute` violates the map's short/non-blocking contract; this was a
  latent risk in the pre-refactor code, not just a style choice.
- Constructor takes `openConsole: (Uuid, Flow<ByteArray>) -> Flow<ByteArray>`
  (a lambda), not the concrete `DataServiceProxy` — `ConsoleRoutes` passes
  `proxy::console`. Makes the refcount state machine unit-testable
  (`ConsoleSessionManagerTest`) without a live gRPC proxy or DB.
- Public API (`getOrCreate(serverId): ConsoleSession`,
  `releaseViewer(serverId)`) unchanged — `ConsoleRoutes.register()` call
  sites untouched.
- See candidate 1, `improve-codebase-architecture` review 2026-07-23.

### DAO Entity seam (master)

The one pattern for database writes and the read projection. Exposed DAO entities
provide automatic dirty tracking; services own `transaction {}` boundaries. See
ADR-0004 (+ its 2026-09-16 amendment).

- **Entity** — `Server(id: EntityID<Uuid>) : UuidEntity(id)`, one `var` per
  column. Exposed generates `UPDATE only_changed_columns …` at flush time.
- **Single projection** — `Server.toServerView()` is the one mapping to the
  detached, immutable **ServerView** (renamed from `ServerRow`). No `ResultRow`
  mapping, no test mirror. Reads query entities.
- **Repository** — exposes reads plus *behavioural* operations. No bare column
  setters (`updateDesiredStatus`/`updateForwardingSecret` were removed — those
  are entity writes in the calling service). A repository survives when it earns
  its keep by **behaviour** (cache + `EntityHook`, allocation, token/alert
  lifecycle) **or leverage** (one query, many call sites) — see the amendment;
  the "drop trivial repos" phase 3 is withdrawn.
- **Delete** — FK `ON DELETE CASCADE` (and `SET_NULL` for `Servers.network_id`)
  is the only cascade. No manual `deleteWhere` ceremony in services.
  `AlertThresholds`/`AlertEvents` are polymorphic (no FK) and are *not* cascaded
  on server delete — tracked separately.
- **Service** — opens `transaction { }`, reads via repo, mutates via entity:
  ```kotlin
  transaction {
      val s = Server.findById(id) ?: throw NotFoundException()
      s.desiredStatus = "RUNNING"
  }
  ```
- **Testing** — fakes store `ServerView` (built via `fakeServerView(...)`);
  `TestDatabase.reset()` keeps referential integrity ON so FK cascade is real.
  `CascadeDeleteTest` asserts it for server/user/group/network roots.
- **Scope** — `Servers` demonstrates the pattern; other tables follow as their
  write paths are touched. `ServerProvisioning` is the create-side entry point.
- See architecture review 2026-07-30 candidate 3, and 2026-09-16 (write seam).

### ServerProvisioning (master)

The one module that turns a `ServerProvisionSpec` into a persisted **Server**. Replaces
`ServerService.createServer`/`cloneServer` and the create-then-overwrite writes duplicated in
`ExportService.importServer`. See ADR-0006.

- `provision(spec): ServerRow` — validates the spec, allocates a host port (with SQLState-23
  retry), and materialises base fields, port registration, env vars, config/stop command,
  proxy fields, exposure/backup overrides, mods and extra ports in **one transaction**.
  All-or-nothing; never swallows failures.
- `clone(sourceId, name, displayName, description): ServerRow` — derives a spec from the
  source's **runtime definition** (env, mods, extra ports, proxy fields, config/stop command,
  container settings). Excludes identity/exposure (hostname, DNS, `exposedExternally`),
  per-instance state (expiry, disabled) and cross-server wiring (proxy backends).
- `ServerProvisionSpec` is a service-layer domain type; wire formats map into it at the seam.
  A `null` override means "derive/seed the default"; a non-null value is verbatim.
- **Import remaps proxy backends by name.** `ProxyBackendExportItem.backend_server_name` is
  exported; network import is two-pass (provision all servers, then wire backends by resolving
  the exported server name — names are unique). A backend absent from the target panel is
  warn-skipped, never self-referenced. Single-server import resolves against existing servers.
- Injected into `ServersRoutes` and `ExportService`. `ServerService` keeps
  update/delete/resources/expiration.

### PortAllocator (master)

The one owner of host-port allocation on a node. Replaces the pure `pickFreePort` object plus two
inline `firstOrNull { it !in usedPorts }` copies — the "read the node's range and used ports, then
pick" step was duplicated across four sites with three different error types/messages.

- `allocate(nodeId, preferred: Int? = null): Int` — reads the node's range via `NodeRepository` and
  its used ports via `PortRepository`; returns `preferred` when free, else the first free port, else
  throws `PortExhaustedException` (mapped to HTTP 409). The node's range is an implementation
  detail — callers ask only for a port on a node.
- `pickFreePort(start, end, used): Int?` stays a pure companion function (internal seam, directly
  unit-tested).
- **Four call sites cross it:** `ServerProvisioning.provision` (primary host port),
  `MigrationCoordinator.allocateRsyncPort` (rsync port), `AssignTargetPortStep` (target host port,
  `preferred = existing`), and `ServerExtraPortRepositoryImpl.createExtraPort` (extra port). The
  first three previously inlined the pick; the fourth already used the pure function.
- **Explicit-hostPort stays a validation, not an allocation:** `createExtraPort`'s "user named a
  specific port" branch keeps its in-transaction collision check (`ConflictException`); only the
  auto branch calls `allocate`. The PortRegistry PK `(nodeId, port, protocol)` is the backstop.
- Injected via Koin (`single { PortAllocator(get(), get()) }`); `ServerProvisioning` and
  `MigrationService` take `portAllocator` instead of `portRepository`.
- Tested at the interface with `FakeNodeRepository` + an in-memory `PortRepository`
  (`PortAllocatorTest`), plus the pure `pickFreePort` cases.
- See candidate 3, `improve-codebase-architecture` review 2026-09-20.

### Settings (master)

The one owner of the system-settings key vocabulary: the typed snapshot every consumer reads.
Replaces the ad-hoc `getAll().associate { key to value }` / `firstOrNull { it.key == "..." }`
lookups that five services each re-derived, with their own key strings, defaults, and
blank-means-unset rules.

- `Settings` — immutable `@Serializable` value object (the 15 settings fields). `Settings.from(rows:
  List<SettingsEntry>): Settings` is the pure companion that owns every key string, its default
  (`app_name` → "CraftPanel", `metric_retention_days` → 30, `image_minecraft` →
  "itzg/minecraft-server", …), and the blank-means-unset rule for nullable fields.
- **`SettingsMap` is deleted**; `SystemSettingsResponse.settings` is `Settings` directly. The wire
  shape is unchanged (same `@SerialName` fields), so the OpenAPI contract and frontend type only
  change name (`SettingsMap` → `Settings`).
- **Five consumers cross it:** `SystemService.loadSettings` (builds it for the response),
  `ServerExposure.resolveGlobalDns`/`resolveSuffix`, `ServerService.deleteServer` (`dns_zone_id`),
  `ServerProvisioning` (`app_name`), `BrandingService` (`app_logo`).
- `updatedAt`/`updatedBy` stay on `SystemSettingsResponse` (derived from `rows.maxByOrNull`), not on
  `Settings`.
- No caching: `Settings.from(settingsRepository.getAll())` per call, matching prior behaviour. A
  cached provider is a separate decision.
- Tested pure (`SettingsTest`): defaults on empty rows, overrides, blank handling, parse fallback.
- See candidate 4, `improve-codebase-architecture` review 2026-09-20.

### ContainerNames (common)

The one owner of the Docker name convention, shared by master and agent via the `:common` module.
`class ContainerNames(prefix)` — prefix-derived, per-server names only: `container(serverId)`,
`sharedNetwork(networkId)`, `standaloneNetwork(serverId)`, `rsyncReceive(migrationId)`,
`rsyncSend(migrationId, final)`, `serverIdOf(name)` (strict inverse — throws on a name that is not
ours), `isManagedContainerName(name)`, `isManagedNetwork(name)`.

- Replaces every raw `"$prefix-…"` construction in both modules; a drifted copy caused the
  custom-prefix network leak (`ContainerHandler`) and a hardcoded backup container name
  (`BackupService`).
- Callers wrap their existing `containerNamePrefix` once — construction sites and DI are unchanged.
- Host-global names (the `craftpanel` network, the mc-router container) are **not** prefix-derived;
  they are one-per-host infrastructure supplied as explicit config.
- See ADR-0007.

### ContainerSpecDiff (agent)

The one module that answers "does the live container satisfy the desired spec?" — the
recreate-if-diff decision. Pure: `ContainerSpecDiff.diff(spec, snapshot, hostDataBasePath): SpecDiff`,
where `SpecDiff` is `Match` or `Mismatch(reasons)` with `SpecDiffReason` ∈ `IMAGE`, `USER`, `MEMORY`,
`CPU`, `ENV`, `BIND`, `PORTS`, `HOSTNAME_LABEL`, `NETWORK_MODE`.

- `ContainerOperator.diff(snapshot, spec)` supplies `config.hostDataBasePath`; the comparison itself
  is pure and unit-tested directly with constructed `ContainerSnapshot`s (no fake needed).
- `ConvergenceLoop` maps `diff is Match` to `ActualState.specMatches` and logs `Mismatch.reasons`
  when a recreate is decided — replacing the old reason-less `matches(): Boolean`.
- `stop_command` is never compared (agent-side action, not container config); an empty spec field
  means "not managed" and its snapshot counterpart is not checked.
- `DockerContainerManager.inspectContainer` (docker-inspect → `ContainerSnapshot`) now has its own
  test with a constructed inspect response — the untested half the `FakeContainerManager` mirror
  could never cover.
- See architecture review 2026-09-16, candidate 4.

### WatcherGate (agent)

The one module that decides "is this container death a crash worth reporting?"
Pure state machine over two server-id sets (`managed`, `stopping`); extracted
from `ContainerManager`, which previously mixed Docker I/O with gating state.

- `shouldReportDie(serverId)` — true iff the server is managed and not
  stopping. Wired by `ControlStreamHandler` as the `ContainerEventWatcher`
  `shouldReport` lambda.
- **Gating lives inside the adapter**: `DockerContainerManager` (the
  `ContainerManager` interface's Docker-backed adapter) marks internally —
  stop/kill/remove mark stopping before the docker call, start marks started on
  success, remove marks removed after. Handlers make zero mark\* calls; the
  invariant "a death this agent caused is never reported as a crash" is
  unbreakable from outside the module.
- Server ids are derived from container names
  (`$containerNamePrefix-$serverId`) inside the adapter — the same convention
  `execRconCommand` already relied on; master builds every command's
  containerName that way (verified across `ContainerLifecycle`/`ServerService`).
- `ContainerManager` is now an interface (Docker ops, gating built in);
  `DockerContainerManager` is the prod adapter; `FakeContainerManager` (test
  source) is the behavioral fake — in-memory container state machine + real
  `WatcherGate` + call log. Two adapters make the seam real.
- `ContainerHandler` tests assert outcomes (die suppressed after stop, restored
  after restart) through the fake instead of call shapes through relaxed
  MockK — first coverage for the recreate flow.
- See `improve-codebase-architecture` review 2026-09-11, candidate 1.

### ConnectionGraph + MetricsPump (agent)

Two deepenings of the agent's per-connection wiring.

- **`ConnectionGraph`** (`agent/grpc/ConnectionGraph.kt`) — the per-connection object
  graph, built once authenticated and torn down when the stream dies.
  `ConnectionGraph.create(koin, config, docker, containerManager, metricsCollector,
  gate, channel, identity, routerSupervisor, networkManager)` builds the Koin
  `ConnectionScope`, the two outbound lanes, `AgentOutbound`, the convergence scope +
  `ConvergenceLoop`/`ContainerOperator`, the `MetricsPump`, and the
  `ControlStreamHandler`/`CommandDispatcher`/`BulkDataClient`; `close()` tears all of
  it down (scope.close + convergenceScope.cancel + channel.shutdown).
  `ConnectionManager.run()` keeps only the reconnect loop, channel creation, auth,
  backoff and the once-per-process router/network supervisor.
- **`MetricsPump`** (`agent/grpc/MetricsPump.kt`) — the one owner of the periodic
  metrics loop (node metrics, per-container metrics, player counts on the telemetry
  lane). `run()` is the interval loop (interval applied after a tick, and it swallows
  non-cancellation failures); `internal suspend fun tick()` is the test surface,
  driven directly with fakes. The CPU-limit lookup is injected as
  `cpuLimitMillicores: (String) -> Int` (from `ConvergenceLoop`), so the pump doesn't
  depend on the loop. Previously inline in `ControlStreamHandler.run()` and untestable.
- **One status seam:** `AgentOutbound.withStatus(serverId, success, log, context, block)`
  replaces the four inlined STARTING/HEALTHY/UNHEALTHY blocks in `ConvergenceLoop` and
  the near-duplicate `AgentUtils.withStatus` (deleted). It uses non-blocking
  `tryServerStatus`, so statuses are best-effort; `ContainerHandler.handleRemove`'s
  status becomes best-effort as a result.
- Tested: `MetricsPumpTest` (tick emit/fan-out/skip/loop-resilience),
  `AgentOutboundTest` gains `withStatus` cases.
- See candidate 7, `improve-codebase-architecture` review 2026-09-20.

## Open / planned

### Server lifecycle orchestrator (master) — superseded by ContainerLifecycle

Resolved: `ContainerLifecycle` is the orchestrator. Owns container command
sequencing with step-gated awaiting. See `plans/c2-container-lifecycle.md`.
