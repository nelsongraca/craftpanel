package io.craftpanel.master.service.repo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

private data class Thing(val id: Uuid, val value: String)

private class FakeCachedRepository : AbstractCachedRepository<Thing>() {
    var loadCallCount = 0
        private set
    var loadResult: Thing? = null

    fun find(id: Uuid): Thing? = cachedFindById(id) {
        loadCallCount++
        loadResult
    }

    fun evict(id: Uuid) = invalidate(id)
}

class AbstractCachedRepositoryTest :
    FunSpec({

        test("first call invokes load, second call hits cache") {
            val repo = FakeCachedRepository()
            val id = Uuid.random()
            repo.loadResult = Thing(id, "a")

            repo.find(id) shouldBe Thing(id, "a")
            repo.find(id) shouldBe Thing(id, "a")

            repo.loadCallCount shouldBe 1
        }

        test("invalidate clears the cache, next call re-invokes load") {
            val repo = FakeCachedRepository()
            val id = Uuid.random()
            repo.loadResult = Thing(id, "a")
            repo.find(id)
            repo.loadCallCount shouldBe 1

            repo.evict(id)
            repo.loadResult = Thing(id, "b")
            repo.find(id) shouldBe Thing(id, "b")

            repo.loadCallCount shouldBe 2
        }

        test("load returning null does not populate the cache") {
            val repo = FakeCachedRepository()
            val id = Uuid.random()
            repo.loadResult = null

            repo.find(id).shouldBeNull()
            repo.find(id).shouldBeNull()

            repo.loadCallCount shouldBe 2
        }
    })
