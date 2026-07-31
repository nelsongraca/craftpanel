package io.craftpanel.master.service.repo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class AbstractCachedRepositoryTest :
    FunSpec({

        data class TestRow(val id: Uuid, val value: String)

        class CountingRepo : AbstractCachedRepository<TestRow>() {
            var loadCalls = 0
            val db = mutableMapOf<Uuid, TestRow>()

            fun find(id: Uuid): TestRow? = cachedFindById(id) {
                loadCalls++
                db[it]
            }

            fun invalidateForTest(id: Uuid) = invalidate(id)
        }

        test("first cachedFindById call invokes load, subsequent calls do not") {
            val repo = CountingRepo()
            val id = Uuid.random()
            repo.db[id] = TestRow(id, "v1")

            repo.find(id) shouldBe TestRow(id, "v1")
            repo.find(id) shouldBe TestRow(id, "v1")
            repo.loadCalls shouldBe 1
        }

        test("invalidate forces a re-load") {
            val repo = CountingRepo()
            val id = Uuid.random()
            repo.db[id] = TestRow(id, "v1")

            repo.find(id) shouldBe TestRow(id, "v1")
            repo.db[id] = TestRow(id, "v2")
            repo.find(id) shouldBe TestRow(id, "v1")

            repo.invalidateForTest(id)
            repo.find(id) shouldBe TestRow(id, "v2")
            repo.loadCalls shouldBe 2
        }

        test("null load result is not cached") {
            val repo = CountingRepo()
            val missing = Uuid.random()

            repo.find(missing) shouldBe null
            repo.find(missing) shouldBe null
            repo.loadCalls shouldBe 2
        }
    })
