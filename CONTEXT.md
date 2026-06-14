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

## Open / planned

### Server lifecycle orchestrator (master) — planned
One module owning the **Container** recreation sequence (conditionally pull image
→ remove → create → start) and the **Needs Recreate** decision, replacing the four
open-coded copies in `ServerService.startServer/restartServer/updateExposure` and
`MigrationService.runMigration`. Pairs with promoting **Status** from `String` to a
closed type that owns the legal transitions. (Being grilled.)
