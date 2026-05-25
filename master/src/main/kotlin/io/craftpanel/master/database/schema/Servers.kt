package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Servers : Table("servers") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100).uniqueIndex()
    val nodeId = uuid("node_id").references(Nodes.id)
    val networkId = uuid("network_id").references(ServerNetworks.id).nullable()
    val status = varchar("status", 10).default("STOPPED")  // STOPPED|STARTING|RUNNING|STOPPING|ERROR
    val mcImage = varchar("mc_image", 255).default("itzg/minecraft-server:latest")
    val gamePort = integer("game_port")
    val memoryMb = integer("memory_mb")
    val cpuShares = integer("cpu_shares").default(0)        // 0 = unlimited
    val cfRecordId = varchar("cf_record_id", 100).nullable()
    val cfRecordName = varchar("cf_record_name", 255).nullable()
    val configMode = varchar("config_mode", 10).default("MANAGED")  // MANAGED | MANUAL
    val stopCommand = varchar("stop_command", 50).default("stop")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
