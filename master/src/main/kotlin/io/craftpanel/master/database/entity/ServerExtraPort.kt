package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ServerExtraPorts
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class ServerExtraPort(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ServerExtraPort>(ServerExtraPorts)

    var serverId by ServerExtraPorts.serverId
    var nodeId by ServerExtraPorts.nodeId
    var name by ServerExtraPorts.name
    var containerPort by ServerExtraPorts.containerPort
    var hostPort by ServerExtraPorts.hostPort
    var protocol by ServerExtraPorts.protocol
    var createdAt by ServerExtraPorts.createdAt
    var updatedAt by ServerExtraPorts.updatedAt
}
