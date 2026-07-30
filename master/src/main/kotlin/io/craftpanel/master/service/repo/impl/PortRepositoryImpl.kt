package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class PortRepositoryImpl : PortRepository {

    override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = transaction {
        PortRegistry.selectAll()
            .where { PortRegistry.nodeId eq nodeId }
            .map { it[PortRegistry.port] }
    }
}