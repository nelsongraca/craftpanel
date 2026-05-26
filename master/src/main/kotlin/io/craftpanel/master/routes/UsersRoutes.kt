package io.craftpanel.master.routes

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.database.schema.Users
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Serializable
data class CreateUserRequest(val username: String, val email: String, val password: String)

@Serializable
data class UserResponse(val id: String, val username: String, val email: String, val isActive: Boolean)

fun Route.usersRoutes() {
    authenticate("auth-jwt") {
        route("/api/v1/users") {
            get("", {
                summary = "List users"
                response {
                    code(HttpStatusCode.OK) { body<List<UserResponse>>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                // TODO: permission check — system.users
                val users = transaction {
                    Users.selectAll().map {
                        UserResponse(
                            id = it[Users.id].toString(),
                            username = it[Users.username],
                            email = it[Users.email],
                            isActive = it[Users.isActive],
                        )
                    }
                }
                call.respond(users)
            }

            post("", {
                summary = "Create user"
                request { body<CreateUserRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<IdResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                // TODO: permission check — system.users
                val req = call.receive<CreateUserRequest>()
                val hash = Argon2Hasher.hash(req.password)

                val generatedId = transaction {
                    Users.insert {
                        it[Users.username] = req.username
                        it[Users.email] = req.email
                        it[Users.passwordHash] = hash
                    }[Users.id]
                }

                call.respond(HttpStatusCode.Created, IdResponse(generatedId.toString()))
            }
        }
    }
}
