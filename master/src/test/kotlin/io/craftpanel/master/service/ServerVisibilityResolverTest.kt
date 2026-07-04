package io.craftpanel.master.service

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.service.repo.FakeGroupRepository
import io.craftpanel.master.service.repo.FakeUserRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class ServerVisibilityResolverTest : FunSpec({

    test("inactive user gets no visibility") {
        val users = FakeUserRepository()
        val groups = FakeGroupRepository()
        val resolver = ServerVisibilityResolver(users, groups)

        val userId = Uuid.random()
        // never created -> isActive() returns false in fake

        val result = resolver.resolve(userId)

        result.isGlobal shouldBe false
        result.networkIds shouldBe emptySet()
        result.serverIds shouldBe emptySet()
    }

    test("active user with global view assignment gets global visibility") {
        val users = FakeUserRepository()
        val groups = FakeGroupRepository()
        val resolver = ServerVisibilityResolver(users, groups)

        val user = users.create("alice", "alice@example.com", "hash")
        val group = groups.create("Viewers", isSystem = false)
        groups.setPermissions(group.id, listOf(Permission.SERVER_VIEW.node))
        users.createAssignment(user.id, group.id, ScopeType.GLOBAL.name, null)

        val result = resolver.resolve(user.id)

        result.isGlobal shouldBe true
    }

    test("active user with server-scoped view assignment gets that server only") {
        val users = FakeUserRepository()
        val groups = FakeGroupRepository()
        val resolver = ServerVisibilityResolver(users, groups)

        val user = users.create("bob", "bob@example.com", "hash")
        val group = groups.create("Viewers", isSystem = false)
        groups.setPermissions(group.id, listOf(Permission.SERVER_VIEW.node))
        val serverId = Uuid.random()
        users.createAssignment(user.id, group.id, ScopeType.SERVER.name, serverId)

        val result = resolver.resolve(user.id)

        result.isGlobal shouldBe false
        result.serverIds shouldBe setOf(serverId)
        result.networkIds shouldBe emptySet()
    }

    test("assignment without view permission grants no visibility") {
        val users = FakeUserRepository()
        val groups = FakeGroupRepository()
        val resolver = ServerVisibilityResolver(users, groups)

        val user = users.create("carol", "carol@example.com", "hash")
        val group = groups.create("NoView", isSystem = false)
        users.createAssignment(user.id, group.id, ScopeType.GLOBAL.name, null)

        val result = resolver.resolve(user.id)

        result.isGlobal shouldBe false
    }
})
