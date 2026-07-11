package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.PortRegistry
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class PortRepositoryImpl : PortRepository {

    override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = transaction {
        PortRegistry.selectAll()
            .where { PortRegistry.nodeId eq nodeId }
            .map { it[PortRegistry.port] }
    }

    override fun registerPort(nodeId: Uuid, port: Int, protocol: String, serverId: Uuid?) {
        transaction {
            PortRegistry.insert {
                it[PortRegistry.nodeId] = nodeId
                it[PortRegistry.port] = port
                it[PortRegistry.protocol] = protocol
                it[PortRegistry.serverId] = serverId
            }
        }
    }

    override fun releasePort(nodeId: Uuid, port: Int, protocol: String) {
        transaction {
            PortRegistry.deleteWhere {
                (PortRegistry.nodeId eq nodeId) and
                        (PortRegistry.port eq port) and
                        (PortRegistry.protocol eq protocol)
            }
        }
    }

    override fun releasePortsForServer(serverId: Uuid) {
        transaction { PortRegistry.deleteWhere { PortRegistry.serverId eq serverId } }
    }

    override fun releasePortsForServerOnNode(serverId: Uuid, nodeId: Uuid) {
        transaction {
            PortRegistry.deleteWhere {
                (PortRegistry.serverId eq serverId) and (PortRegistry.nodeId eq nodeId)
            }
        }
    }
}
