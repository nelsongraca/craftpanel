# Server provisioning materialises a spec atomically; import remaps proxy backends by name

## Status

accepted

## Context

Server creation was spread across three callers that each re-enumerated the same fields:
`ServerService.createServer` (REST create), `ServerService.cloneServer`, and
`ExportService.importServer`. `createServer` seeded default env vars and every caller then
deleted those defaults and re-inserted its own (`cloneServer`, `importServer`), and
`importServer` patched a second set of columns (`configMode`, `stopCommand`, proxy/backup
fields) in a follow-up transaction. A new server column therefore had to be threaded through
three places.

Import also swallowed sub-resource failures with `runCatching` (extra ports, proxy backends),
so an import could leave a partially-populated server. And `ProxyBackends.backendServerId` is
a non-null FK to `Servers`, but the export format carried only the *original* server id — so
`importServer` set `backendServerId` to the proxy's own new id, self-referencing the proxy to
satisfy the FK. Because a proxy's backend server can appear later in a network export, the
correct value is not known when the proxy itself is imported.

## Decision

Introduce **`ServerProvisioning`**, one module that turns a **`ServerProvisionSpec`** into a
persisted server. The spec is a service-layer domain type, independent of any wire format;
callers map into it at the seam (REST `CreateServerRequest`, export `ServerExportData`, and
`clone`, which derives the spec from the source).

- **Full materialisation.** `provision(spec)` creates the base server, port registration, env
  vars, config/stop command, proxy fields, exposure/backup overrides, mods and extra ports in
  one pass. `null` on an override means "derive/seed the default"; a non-null value is applied
  verbatim. No create-then-overwrite.
- **Atomic and strict.** The spec is validated up front, then materialised in a single
  transaction; the SQLState-23 port-collision retry wraps that transaction. `provision` never
  swallows a failure. Anything the caller cannot express in the spec (cross-server proxy
  backends) is resolved by the caller, not ignored by the module.
- **Clone is runtime-faithful.** `clone` copies the runtime definition — env vars, mods, extra
  ports, proxy fields, config/stop command, container settings. It deliberately excludes
  identity/exposure (hostname, DNS, `exposedExternally`), per-instance state (expiry, disabled)
  and cross-server wiring (proxy backends).
- **Import remaps proxy backends by server name.** `ProxyBackendExportItem` gains
  `backend_server_name`. Network import runs two passes: pass 1 provisions every server; pass 2
  wires each proxy's backends by resolving `backend_server_name` to the now-existing server
  (names are unique). A backend whose server is absent from the target panel is skipped with a
  warning. A single-server import resolves against servers that already exist. The original id
  is never used as a new id.
- `ServerService` keeps update/delete/resources/expiration; `createServer`/`cloneServer` are
  deleted, and `ServerProvisioning` is injected into `ServersRoutes` and `ExportService`.

## Consequences

- Adding a server column touches `ServerProvisionSpec` + `provision` only; clone and import
  inherit it through the spec.
- Import is no longer tolerant: an invalid spec fails the whole import instead of leaving a
  partial server. Legacy exports without `backend_server_name` import with proxy backends
  skipped (logged), not self-referenced.
- `ServerProvisioning` writes through Exposed entities and owns its own `transaction {}`
  boundary, consistent with the DAO entity pattern (ADR-0004).
- Cross-node backend addressing remains deferred to #43; only same-node name resolution is
  performed here.

## Rejected alternatives

- **Keep `createServer` as a delegating facade** — re-adds the shallow wrapper the module exists
  to remove.
- **Reuse `ServerExportData` as the spec** — leaks a wire DTO into the service layer and keeps
  export/import coupled to the module's interface.
- **Add an `original_id` to the export and remap by id** — would require a format version bump;
  name matching is sufficient because server names are globally unique and the backend's name is
  already in the export.
- **Warn-skip within `provision`** — tolerates partial servers, the class of bug that produced
  the self-referencing backend.
