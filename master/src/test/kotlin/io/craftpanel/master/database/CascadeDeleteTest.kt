package io.craftpanel.master.database

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.entity.Group
import io.craftpanel.master.database.entity.Network
import io.craftpanel.master.database.entity.Node
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.entity.User
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.MigrationStepLog
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.database.schema.RecoveryCodes
import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.ServerExtraPorts
import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.TrustedDevices
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Confirms the FK `ON DELETE CASCADE` / `SET_NULL` behaviour the services now rely on instead of
 * hand-written cascade deletes. `TestDatabase.reset()` keeps referential integrity enabled, so these
 * run against real constraints.
 */
class CascadeDeleteTest :
    FunSpec({

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun createNode(): Uuid = transaction {
            Node.new {
                hostname = "node-${Uuid.random()}"
                displayName = "node"
                publicIp = "1.2.3.4"
                privateIp = "10.0.0.1"
                tokenHash = Uuid.random().toString().replace("-", "").padEnd(64, 'a').take(64)
                totalRamMb = 8192
            }.id.value
        }

        fun createServer(nodeId: Uuid, networkId: Uuid? = null): Uuid = transaction {
            Server.new {
                name = "srv-${Uuid.random()}"
                displayName = "srv"
                this.nodeId = EntityID(nodeId, Nodes)
                this.networkId = networkId?.let { EntityID(it, ServerNetworks) }
                hostPort = 25565
                memoryMb = 1024
            }.id.value
        }

        fun createUser(): Uuid = transaction {
            User.new {
                username = "u-${Uuid.random()}"
                email = "${Uuid.random()}@example.com"
                passwordHash = "hash"
            }.id.value
        }

        fun createGroup(): Uuid = transaction {
            Group.new { name = "g-${Uuid.random()}" }.id.value
        }

        val now = LocalDateTime(2025, 1, 1, 0, 0)

        test("deleting a server cascades all child rows") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            transaction {
                val sid = EntityID(serverId, Servers)
                val nid = EntityID(nodeId, Nodes)
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = sid
                    it[key] = "K"
                    it[value] = "V"
                }
                ServerMods.insert {
                    it[ServerMods.serverId] = sid
                    it[modrinthProjectId] = "p"
                    it[displayName] = "m"
                    it[pinStrategy] = "LATEST"
                }
                ServerJobs.insert {
                    it[ServerJobs.serverId] = sid
                    it[type] = "BACKUP"
                    it[cronExpression] = "* * * * *"
                }
                val migrationId = ServerMigrations.insert {
                    it[ServerMigrations.serverId] = sid
                    it[sourceNodeId] = nid
                    it[targetNodeId] = nid
                    it[status] = "COMPLETED"
                }[ServerMigrations.id]
                PortRegistry.insert {
                    it[PortRegistry.nodeId] = nid
                    it[port] = 25566
                    it[protocol] = "TCP"
                    it[PortRegistry.serverId] = sid
                }
                Backups.insert {
                    it[Backups.serverId] = sid
                    it[Backups.nodeId] = nid
                    it[trigger] = "MANUAL"
                    it[status] = "COMPLETED"
                }
                ContainerMetrics.insert {
                    it[ContainerMetrics.serverId] = sid
                    it[recordedAt] = now
                    it[cpuPercent] = 1.0
                    it[ramUsedMb] = 10
                    it[netInBytes] = 0
                    it[netOutBytes] = 0
                    it[blockInBytes] = 0
                    it[blockOutBytes] = 0
                }
                ProxyBackends.insert {
                    it[proxyServerId] = sid
                    it[backendServerId] = sid
                    it[backendName] = "b"
                }
                ServerExtraPorts.insert {
                    it[ServerExtraPorts.serverId] = sid
                    it[ServerExtraPorts.nodeId] = nid
                    it[name] = "query"
                    it[containerPort] = 25565
                    it[hostPort] = 25567
                }
                MigrationStepLog.insert {
                    it[MigrationStepLog.migrationId] = migrationId
                    it[stepNumber] = 1
                    it[description] = "step"
                    it[status] = "SUCCESS"
                }
            }

            transaction { Server.findById(serverId)?.delete() }

            transaction {
                ServerEnvVars.selectAll().count() shouldBe 0L
                ServerMods.selectAll().count() shouldBe 0L
                ServerJobs.selectAll().count() shouldBe 0L
                ServerMigrations.selectAll().count() shouldBe 0L
                MigrationStepLog.selectAll().count() shouldBe 0L
                PortRegistry.selectAll().count() shouldBe 0L
                Backups.selectAll().count() shouldBe 0L
                ContainerMetrics.selectAll().count() shouldBe 0L
                ProxyBackends.selectAll().count() shouldBe 0L
                ServerExtraPorts.selectAll().count() shouldBe 0L
            }
        }

        test("deleting a user cascades assignments, tokens, recovery codes and trusted devices") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            val userId = createUser()
            val groupId = createGroup()

            transaction {
                val uid = EntityID(userId, Users)
                val gid = EntityID(groupId, Groups)
                UserGroupAssignments.insert {
                    it[UserGroupAssignments.userId] = uid
                    it[UserGroupAssignments.groupId] = gid
                    it[scopeType] = "GLOBAL"
                }
                RefreshTokens.insert {
                    it[RefreshTokens.userId] = uid
                    it[tokenHash] = "h".repeat(64)
                    it[expiresAt] = now
                }
                RecoveryCodes.insert {
                    it[RecoveryCodes.userId] = uid
                    it[codeHash] = "c".repeat(64)
                }
                TrustedDevices.insert {
                    it[TrustedDevices.userId] = uid
                    it[tokenHash] = "t".repeat(64)
                    it[deviceFingerprint] = "f".repeat(64)
                    it[userAgent] = "test"
                    it[expiresAt] = now
                }
            }

            transaction { User.findById(userId)?.delete() }

            transaction {
                UserGroupAssignments.selectAll().count() shouldBe 0L
                RefreshTokens.selectAll().count() shouldBe 0L
                RecoveryCodes.selectAll().count() shouldBe 0L
                TrustedDevices.selectAll().count() shouldBe 0L
            }
            // the server is untouched by a user delete
            transaction { Servers.selectAll().count() shouldBe 1L }
        }

        test("deleting a group cascades permissions and assignments") {
            val userId = createUser()
            val groupId = createGroup()

            transaction {
                val gid = EntityID(groupId, Groups)
                GroupPermissions.insert {
                    it[GroupPermissions.groupId] = gid
                    it[permission] = "server.view"
                }
                UserGroupAssignments.insert {
                    it[UserGroupAssignments.userId] = EntityID(userId, Users)
                    it[UserGroupAssignments.groupId] = gid
                    it[scopeType] = "GLOBAL"
                }
            }

            transaction { Group.findById(groupId)?.delete() }

            transaction {
                GroupPermissions.selectAll().where { GroupPermissions.groupId eq EntityID(groupId, Groups) }.count() shouldBe 0L
                UserGroupAssignments.selectAll().where { UserGroupAssignments.groupId eq EntityID(groupId, Groups) }.count() shouldBe 0L
            }
        }

        test("deleting a network detaches member servers via SET NULL") {
            val nodeId = createNode()
            val networkId = transaction { Network.new { name = "net-${Uuid.random()}" }.id.value }
            val serverId = createServer(nodeId, networkId)

            transaction { Network.findById(networkId)?.delete() }

            transaction { Server.findById(serverId)!!.networkId.shouldBeNull() }
            transaction { ServerNetworks.selectAll().count() shouldBe 0L }
        }
    })
