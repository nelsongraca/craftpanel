package io.craftpanel.master.auth

import io.craftpanel.master.service.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import kotlin.uuid.Uuid

/** Result of a successful server-scoped authorization check. */
data class AuthorizedServer(val serverId: Uuid, val networkId: Uuid?, val userId: Uuid)

/** The authenticated user id parsed from the JWT principal. The one JWT-user helper in master. */
fun ApplicationCall.authUserId(): Uuid = Uuid.parse(principal<JWTPrincipal>()!!.payload.subject)

/**
 * Server-scoped authorization seam: parse `{id}`, resolve its network scope, and
 * check [permission]. Throws on any failure (mapped to 400/404/403 by StatusPages);
 * returns the resolved scope on success.
 *
 * Replaces the copy-pasted prelude (parse → scope → hasPermission) in every
 * server-scoped handler.
 */
fun ApplicationCall.requireServerPermission(permission: Permission): AuthorizedServer {
    val userId = authUserId()
    val serverId = parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw BadRequestException("Invalid server ID")
    return requireServerPermission(serverId, permission, userId)
}

/**
 * Server-scoped guard for handlers whose server id does not come from the `{id}` path parameter
 * (e.g. derived from a `migrationId`). Same failure modes as [requireServerPermission].
 */
fun ApplicationCall.requireServerPermission(serverId: Uuid, permission: Permission): AuthorizedServer = requireServerPermission(serverId, permission, authUserId())

private fun requireServerPermission(serverId: Uuid, permission: Permission, userId: Uuid): AuthorizedServer {
    val scope = ServerLookup.scope(serverId)
        ?: throw NotFoundException("Server not found")
    if (!PermissionResolver.hasPermission(userId, permission, serverId = serverId, networkId = scope.networkId)) {
        throw ForbiddenException("Insufficient permissions")
    }
    return AuthorizedServer(serverId, scope.networkId, userId)
}

/**
 * Network-scoped guard: parse `{id}`, confirm the network exists, and check [permission].
 * Throws [BadRequestException]/[NotFoundException]/[ForbiddenException] on failure; returns the
 * resolved network id on success.
 */
fun ApplicationCall.requireNetworkPermission(permission: Permission): Uuid {
    val networkId = parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw BadRequestException("Invalid network ID")
    if (!NetworkLookup.exists(networkId)) throw NotFoundException("Network not found")
    requireNetworkPermission(networkId, permission)
    return networkId
}

/**
 * Network-scoped guard when the network id comes from elsewhere (e.g. a request body) rather than
 * the `{id}` path parameter. The caller/service owns the "network exists" check.
 */
fun ApplicationCall.requireNetworkPermission(networkId: Uuid, permission: Permission) {
    val userId = authUserId()
    if (!PermissionResolver.hasPermission(userId, permission, networkId = networkId)) {
        throw ForbiddenException("Insufficient permissions")
    }
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
