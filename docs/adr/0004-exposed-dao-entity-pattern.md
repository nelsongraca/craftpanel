# Exposed DAO entity pattern for database writes

## Status

accepted

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
