package io.craftpanel.master.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class GrantIndexTest :
    FunSpec({

        val serverA = Uuid.random()
        val networkA = Uuid.random()
        val globalGroup = Uuid.random()
        val serverGroup = Uuid.random()
        val networkGroup = Uuid.random()

        fun index() = GrantIndex.from(
            assignments = listOf(
                AssignmentScope(globalGroup, ScopeType.GLOBAL.name, null),
                AssignmentScope(serverGroup, ScopeType.SERVER.name, serverA),
                AssignmentScope(networkGroup, ScopeType.NETWORK.name, networkA)
            ),
            permissionsByGroup = mapOf(
                globalGroup to setOf("system.nodes"),
                serverGroup to setOf("server.start", "server.view"),
                networkGroup to setOf("server.stop", "server.view")
            )
        )

        test("permissionsFor unions the global, server and network scopes") {
            index().permissionsFor(serverA, networkA) shouldContainExactlyInAnyOrder
                listOf("system.nodes", "server.start", "server.view", "server.stop")
        }

        test("permissionsFor omits scopes that were not requested") {
            index().permissionsFor() shouldContainExactlyInAnyOrder listOf("system.nodes")
            index().permissionsFor(serverId = serverA) shouldContainExactlyInAnyOrder
                listOf("system.nodes", "server.start", "server.view")
        }

        test("two groups on the same server both contribute (no last-write overwrite)") {
            val g1 = Uuid.random()
            val g2 = Uuid.random()
            val idx = GrantIndex.from(
                assignments = listOf(
                    AssignmentScope(g1, ScopeType.SERVER.name, serverA),
                    AssignmentScope(g2, ScopeType.SERVER.name, serverA)
                ),
                permissionsByGroup = mapOf(g1 to setOf("server.start"), g2 to setOf("server.stop"))
            )
            idx.permissionsFor(serverId = serverA) shouldContainExactlyInAnyOrder listOf("server.start", "server.stop")
        }

        test("scopesGranting reports every scope that holds the permission") {
            val scopes = index().scopesGranting(Permission.SERVER_VIEW)
            scopes.global shouldBe false
            scopes.serverIds shouldBe setOf(serverA)
            scopes.networkIds shouldBe setOf(networkA)
        }

        test("scopesGranting honours wildcards and reports empty when ungranted") {
            val g = Uuid.random()
            val idx = GrantIndex.from(
                assignments = listOf(AssignmentScope(g, ScopeType.GLOBAL.name, null)),
                permissionsByGroup = mapOf(g to setOf("server.*"))
            )
            idx.scopesGranting(Permission.SERVER_START).global shouldBe true
            idx.scopesGranting(Permission.NETWORK_VIEW).isEmpty shouldBe true
        }

        test("an assignment with no scope id is ignored") {
            val g = Uuid.random()
            val idx = GrantIndex.from(
                assignments = listOf(AssignmentScope(g, ScopeType.SERVER.name, null)),
                permissionsByGroup = mapOf(g to setOf("server.start"))
            )
            idx.permissionsFor(serverId = serverA).isEmpty() shouldBe true
            idx.scopesGranting(Permission.SERVER_START).isEmpty shouldBe true
        }
    })
