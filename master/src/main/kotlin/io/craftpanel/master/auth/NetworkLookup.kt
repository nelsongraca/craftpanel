package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.ServerNetworks
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Existence check for a Server Network, mirroring [ServerLookup] so the authorization seam can
 * distinguish "no such network" (404) from "not allowed" (403) consistently.
 */
object NetworkLookup {

    fun exists(networkId: Uuid): Boolean = transaction {
        ServerNetworks.selectAll()
            .where { ServerNetworks.id eq networkId }
            .any()
    }
}
