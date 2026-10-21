package io.craftpanel.master.service

import io.craftpanel.common.PortProtocols
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.Servers
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import kotlin.uuid.Uuid

/**
 * The single owner of [PortRegistry] writes for server-owned ports (primary and extra).
 *
 * A stored protocol of `BOTH` is expanded into one row per concrete transport, so the registry is
 * an accurate socket-level reservation and the existing `(node_id, port, protocol)` primary key
 * rejects a genuine double-bind.
 *
 * Every function must be called from inside a transaction. Registering `BOTH` therefore rolls back
 * as a unit if one of the two rows loses a race — which is what lets `ServerProvisioning` retry on
 * a `SQLState 23` violation.
 */
object ServerPortRegistry {

    /** Registers [port] on [nodeId] for every concrete protocol [protocol] expands to. */
    fun register(nodeId: Uuid, serverId: Uuid, port: Int, protocol: String) {
        PortProtocols.expand(protocol).forEach { concrete ->
            PortRegistry.insert {
                it[PortRegistry.nodeId] = EntityID(nodeId, Nodes)
                it[PortRegistry.port] = port
                it[PortRegistry.protocol] = concrete
                it[PortRegistry.serverId] = EntityID(serverId, Servers)
            }
        }
    }

    /** Replaces every protocol row for [serverId]'s [port] with the rows [protocol] expands to. */
    fun replace(nodeId: Uuid, serverId: Uuid, port: Int, protocol: String) {
        unregister(serverId, port)
        register(nodeId, serverId, port, protocol)
    }

    /** Removes every protocol row for [serverId]'s [port]. */
    fun unregister(serverId: Uuid, port: Int) {
        PortRegistry.deleteWhere {
            (PortRegistry.serverId eq serverId) and (PortRegistry.port eq port)
        }
    }

    /** Removes every registry row owned by [serverId] (primary and extra ports). Migration only. */
    fun unregisterAll(serverId: Uuid) {
        PortRegistry.deleteWhere { PortRegistry.serverId eq serverId }
    }
}
