package io.craftpanel.master.routes

import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Serializable
data class GroupResponse(val id: String, val name: String, val isSystem: Boolean, val permissions: List<String>)

fun Route.groupsRoutes() {
    authenticate("auth-jwt") {
        route("/api/v1/groups") {
            get("", {
                summary = "List groups"
                response {
                    code(HttpStatusCode.OK) { body<List<GroupResponse>>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                // TODO: permission check — system.users
                val groups = transaction {
                    val allPerms = GroupPermissions.selectAll()
                        .groupBy { it[GroupPermissions.groupId] }
                        .mapValues { (_, rows) -> rows.map { it[GroupPermissions.permission] } }

                    Groups.selectAll().map {
                        val groupId = it[Groups.id]
                        GroupResponse(
                            id = groupId.toString(),
                            name = it[Groups.name],
                            isSystem = it[Groups.isSystem],
                            permissions = allPerms[groupId] ?: emptyList(),
                        )
                    }
                }
                call.respond(groups)
            }
        }
    }
}
