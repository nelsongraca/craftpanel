package io.craftpanel.master.auth

import io.craftpanel.master.service.BadRequestException
import io.craftpanel.master.service.ForbiddenException
import io.craftpanel.master.service.NotFoundException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import kotlin.uuid.Uuid

/** Result of a successful server-scoped authorization check. */
data class AuthorizedServer(val serverId: Uuid, val networkId: Uuid?, val userId: Uuid)

private fun ApplicationCall.authUserId(): Uuid =
    Uuid.parse(principal<JWTPrincipal>()!!.payload.subject)

/**
 * Server-scoped authorization seam: parse `{id}`, resolve its network scope, and
 * check [permission]. Throws on any failure (mapped to 400/404/403 by StatusPages);
 * returns the resolved scope on success.
 *
 * Replaces the copy-pasted prelude (parse → authInfo → hasPermission) in every
 * server-scoped handler.
 */
fun ApplicationCall.requireServerPermission(permission: Permission): AuthorizedServer {
    val userId = authUserId()
    val serverId = parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw BadRequestException("Invalid server ID")
    val scope = ServerLookup.scope(serverId)
        ?: throw NotFoundException("Server not found")
    if (!PermissionResolver.hasPermission(userId, permission, serverId = serverId, networkId = scope.networkId)) {
        throw ForbiddenException("Insufficient permissions")
    }
    return AuthorizedServer(serverId, scope.networkId, userId)
}

/**
 * Global/network-scoped authorization seam: check [permission] with no resource id.
 * Throws [ForbiddenException] (mapped to 403) on failure.
 */
fun ApplicationCall.requirePermission(permission: Permission) {
    val userId = authUserId()
    if (!PermissionResolver.hasPermission(userId, permission)) {
        throw ForbiddenException("Insufficient permissions")
    }
}
