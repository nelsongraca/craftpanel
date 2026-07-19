package io.craftpanel.master.service.repo.impl

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Shared read-through cache plumbing for repositories with a `findById(id: Uuid): T?` lookup.
 * Invalidation is intentionally NOT provided beyond single-id [invalidate] — each subclass's
 * write methods must call [invalidate] themselves, since which writes affect which cached rows
 * is repo-specific. Bulk invalidation (e.g. clearing all cached rows referencing a deleted
 * parent) is also left to subclasses for the same reason.
 */
abstract class AbstractCachedRepository<T : Any> {

    protected val cache = ConcurrentHashMap<Uuid, T>()

    protected fun cachedFindById(id: Uuid, load: (Uuid) -> T?): T? = cache[id] ?: load(id)?.also { row -> cache[id] = row }

    protected fun invalidate(id: Uuid) {
        cache.remove(id)
    }
}
