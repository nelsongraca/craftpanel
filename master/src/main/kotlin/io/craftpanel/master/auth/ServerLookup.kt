package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.Servers
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** A server's scope: its (nullable) network id. */
data class ServerScope(val networkId: Uuid?)

/**
 * Single source for resolving a server's network scope, used by the route
 * authorization seam. Replaces the six service-local `getServerScope`/`authInfo`
 * copies that each ran a near-identical `Servers.networkId` query solely to feed
 * the permission check.
 */
object ServerLookup {

    /**
     * Scope of the given server, or `null` if the server does not exist.
     * `networkId` inside the scope may itself be `null` (server with no network).
     */
    fun scope(serverId: Uuid): ServerScope? = transaction {
        Servers.selectAll()
            .where { Servers.id eq serverId }
            .firstOrNull()
            ?.let { ServerScope(it[Servers.networkId]) }
    }
}
