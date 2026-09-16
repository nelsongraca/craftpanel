# Exposed DAO entity pattern for database writes

## Status

accepted

## Amendment (2026-08-01)

The identity-map claim in Decision #6 is **transaction-scoped only** — Exposed's
`EntityCache` batches writes and dedupes repeat reads within a single
`transaction {}` block, then dies with the transaction. It does not survive
across requests or WebSocket events. The process-scoped `findById` cache is
therefore reinstated: `AbstractCachedRepository<ServerRow>` is back, and
`ServerRepositoryImpl` extends it. Invalidation no longer happens at write
sites (the migration removed them); instead `ServerRepositoryImpl` subscribes
to Exposed's `EntityHook` in its init block and evicts the row for any
`Server` entity `Created`/`Updated`/`Removed` flush. Full reasoning in
`.scratch/networkid-cache/DESIGN.md`.

## Context

The master module uses Exposed's DSL `update {}` pattern for database writes.
Every `ServerRepository` update is a named method wrapping a 3-line
transaction with manual cache invalidation:

```kotlin
fun updateStatus(id: Uuid, status: String, lastSeenAt: Instant?) {
    transaction {
        Servers.update({ Servers.id eq id }) {
            it[Servers.status] = status
            if (lastSeenAt != null) it[Servers.lastSeenAt] = lastSeenAt
        }
    }
    invalidate(id)
}
```

There are 16 such methods on `ServerRepository` alone, one per column group.
Every new column adds a new method. The `ServerRow` data class (41 fields) and
`AbstractCachedRepository` (ConcurrentHashMap read-through) must be kept in
sync manually.

Exposed's DAO pattern provides automatic dirty tracking: modifying a property
on an entity inside a transaction generates `UPDATE` with only changed columns
at flush time. This eliminates the update-method surface, the manual cache,
and the parallel data class (the entity IS the row).

Seven repositories follow the same update-method pattern and would benefit
from the same treatment.

## Decision

Use Exposed DAO entities for all database writes moving forward. Pattern:

1. **Table** — stays as-is (`object Servers : Table("servers")`).
2. **Entity** — one per table: `class FooEntity(id: EntityID<Uuid>) : UUIDEntity(id)`.
   One `var` per writable column via delegation.
3. **Repository** — read-side only: `findById`, `listAll`, typed queries.
   Returns `ServerRow` / `FooRow` data classes (read-only projections).
   No update/delete/create methods.
4. **Service** — opens `transaction { }`, reads via repository, mutates via entity:
   ```kotlin
   transaction {
       val s = ServerEntity.findById(id) ?: throw NotFoundException()
       s.status = "STARTING"
       s.lastSeenAt = nowUTC()
   }
   ```
5. **Deletes** — FK `ON DELETE CASCADE` on child tables. No manual cascade
   in `delete()` methods.
6. **Caching** — entity identity-map (transaction-scoped) replaces
   `AbstractCachedRepository`'s process-scoped ConcurrentHashMap. No manual
   `invalidate()` calls needed.
7. **Testing** — existing `FakeServerRepository` (queries only) stays for
   unit tests. Entity-write tests use `TestDatabase` + `transaction { }`.

Phase-in plan:
- Phase 1: `ServerEntity` (C3, this ADR). First table to demonstrate pattern.
- Phase 2: All other tables with update-method repositories (Nodes, Networks,
  Users, Groups, Backups, Mods, Alerts, Settings, …).
- Phase 3 (evaluate): Drop repository interfaces where the read-side is
  trivial, let callers use `FooEntity.findById()` directly. Depends on
  test-infra maturity (H2-based tests vs. fakes).

Rejected alternatives:
- **`ServerPatch` data class** — nullable-all-fields patch type. Works but
  adds a parallel type that mirrors the table, same maintenance cost as
  the current update methods but in one object.
- **Hibernate / Exposed DAO** — this ADR chooses Exposed DAO. Hibernate
  would add a second ORM to the project with no benefit over Exposed's
  built-in dirty tracking.

## Consequences

- `ServerRepository` interface shrinks from 23 methods to ~7 (queries only).
- `AbstractCachedRepository` deleted — replaced by entity identity-map.
- `serverRepository.updateStatus()`, `updateResources()`, etc. replaced by
  `transaction { entity.property = value }` at call sites.
- Services gain explicit `transaction { }` boundaries — they own when flush
  happens, not the repo.
- FK `ON DELETE CASCADE` added to `Servers` for: env_vars, mods, backups,
  ports, migrations, proxy_backends, container_metrics, server_jobs.
- TestDatabase `reset()` needs `TRUNCATE ... CASCADE` instead of FK-safe
  delete ordering.
- Parallel DAO entities for the 14 other tables planned in phase 2 — each
  follows the same pattern, each repository loses its write surface, each
  service gains `transaction {}`.

## Amendment (2026-09-16)

Finishing the write seam. Four corrections and refinements to the above, made
after an architecture review; the original text is superseded where it conflicts.

### Repository survival is "behaviour or leverage", not "drop the trivial"

Phase 3 above proposed dropping repository interfaces "where the read-side is
trivial". The deletion test rejects that as a blanket rule: a one-query
repository with many call sites is **leverage** — deleting it copies the query
into every caller. Measured call-site counts at the time of writing:
`NodeRepository` 15, `GroupRepository`/`ProxyBackendRepository` 7,
`EnvVarsRepository`/`SettingsRepository`/`NetworkRepository` 6, five more at 4,
`AlertRepository` 3, two at 2. Inlining them would have spread raw schema access
back into `AuthRoutes`, `BrandingService`, and schedulers. **Phase 3 is
withdrawn.** A repository stays when it earns its keep by behaviour *or* by
leverage.

### The entity is the single source of the read projection

`ServerRow` is renamed **`ServerView`** and stays as the one detached, immutable
read model (the cache value, the pure-module input, the WS/DTO source).
`Server.toServerView()` is now the **only** mapping to it; the private
`ResultRow.toServerView()` in `ServerRepositoryImpl` and the 41-field
`MutableServer` mirror in `FakeServerRepository` are deleted. Reads query
entities (`Server.find { … }`, `Server.findById`); test fakes store `ServerView`
directly, built through the `fakeServerView(...)` factory. The earlier
`AbstractCachedRepository` reinstatement (amendment of 2026-08-01) stands.

### Repositories expose reads plus behaviour — never bare column setters

`ServerRepository.updateDesiredStatus`/`updateForwardingSecret` are removed;
the desired-status intent write is a private entity write in
`ContainerLifecycle`/`ServerLifecycleService`, and the forwarding secret is an
entity write in `BackendForwardingService`. Behavioural operations stay behind
their repository — alert open/resolve, recovery-code consume, extra-port
allocation, refresh-token lifecycle, last-login stamp — because they encode a
domain operation, not a column assignment.

### Deletes are the schema's job

Every FK already declares `onDelete` (`CASCADE`, or `SET_NULL` for
`Servers.network_id`). The hand-written cascade ceremony in
`ServerService.deleteServer` (9 `deleteWhere` calls), `UserService.deleteUser`,
`GroupService.deleteGroup`, and `NetworkService.deleteNetwork` is deleted; each
delete is now the single root-entity `delete()`. `TestDatabase.reset()` no longer
executes `SET REFERENTIAL_INTEGRITY FALSE`, so the suites run against real
constraints. `CascadeDeleteTest` asserts the cascade for server, user, group and
network roots. Caveat: `AlertThresholds`/`AlertEvents` are polymorphic
(`scope_type` + `scope_id`, no FK) and are **not** cascaded on server delete —
unchanged from before, tracked separately.

Naming: the entity is `Server` (not `ServerEntity` as written above), the
projection is `ServerView`.

