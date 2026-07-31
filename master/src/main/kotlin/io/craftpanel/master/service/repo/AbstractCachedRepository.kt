package io.craftpanel.master.service.repo

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

abstract class AbstractCachedRepository<T : Any> {

    protected val cache = ConcurrentHashMap<Uuid, T>()

    protected fun cachedFindById(id: Uuid, load: (Uuid) -> T?): T? = cache[id] ?: load(id)?.also { cache[id] = it }

    protected fun invalidate(id: Uuid) {
        cache.remove(id)
    }
}
