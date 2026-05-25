package io.craftpanel.master.routes

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.database.schema.Users
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
            get {
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

            post {
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

                call.respond(HttpStatusCode.Created, mapOf("id" to generatedId.toString()))
            }
        }
    }
}
