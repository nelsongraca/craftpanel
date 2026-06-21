package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.schema.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

@Serializable
data class AssignmentResponse(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("scope_type") val scopeType: ScopeType,
    @SerialName("scope_id") val scopeId: String?,
)

@Serializable
data class AssignmentsListResponse(val assignments: List<AssignmentResponse>)

@Serializable
data class CreateAssignmentRequest(
    @SerialName("group_id") val groupId: String,
    @SerialName("scope_type") val scopeType: String,
    @SerialName("scope_id") val scopeId: String? = null,
)

class AssignmentService {

    fun listAssignments(targetId: Uuid): AssignmentsListResponse {
        val exists = transaction {
            Users.selectAll()
                .where { Users.id eq targetId }
                .firstOrNull() != null
        }
        if (!exists) throw NotFoundException("User not found")
        val assignments = transaction {
            UserGroupAssignments.selectAll()
                .where { UserGroupAssignments.userId eq targetId }
                .map { it.toAssignmentResponse() }
        }
        return AssignmentsListResponse(assignments)
    }

    fun createAssignment(targetId: Uuid, req: CreateAssignmentRequest): AssignmentResponse {
        if (req.scopeType !in setOf(ScopeType.GLOBAL.name, ScopeType.SERVER.name, ScopeType.NETWORK.name))
            throw UnprocessableException("scope_type must be GLOBAL, SERVER, or NETWORK")
        if (req.scopeType != ScopeType.GLOBAL.name && req.scopeId == null)
            throw UnprocessableException("scope_id required for ${req.scopeType} scope")

        val groupId = runCatching { Uuid.parse(req.groupId) }.getOrNull()
            ?: throw UnprocessableException("Invalid group_id")
        val scopeId = req.scopeId?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
                ?: throw UnprocessableException("Invalid scope_id")
        }

        val validation = transaction {
            val userExists = Users.selectAll()
                .where { Users.id eq targetId }
                .firstOrNull() != null
            val groupExists = Groups.selectAll()
                .where { Groups.id eq groupId }
                .firstOrNull() != null
            val scopeExists = when {
                scopeId == null                         -> true
                req.scopeType == ScopeType.SERVER.name  -> Servers.selectAll()
                    .where { Servers.id eq scopeId }
                    .firstOrNull() != null

                req.scopeType == ScopeType.NETWORK.name -> ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq scopeId }
                    .firstOrNull() != null

                else                                    -> true
            }
            val alreadyExists = UserGroupAssignments.selectAll()
                .where {
                    (UserGroupAssignments.userId eq targetId) and
                            (UserGroupAssignments.groupId eq groupId) and
                            (UserGroupAssignments.scopeType eq req.scopeType) and
                            if (scopeId != null) (UserGroupAssignments.scopeId eq scopeId) else (UserGroupAssignments.scopeId.isNull())
                }
                .firstOrNull() != null
            Triple(userExists && groupExists && scopeExists, alreadyExists, userExists && groupExists)
        }

        if (!validation.third) throw NotFoundException("User or group not found")
        if (!validation.first) throw NotFoundException("Scope target not found")
        if (validation.second) throw ConflictException("Assignment already exists")

        val createdId = transaction {
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = targetId
                it[UserGroupAssignments.groupId] = groupId
                it[UserGroupAssignments.scopeType] = req.scopeType
                it[UserGroupAssignments.scopeId] = scopeId
            }[UserGroupAssignments.id]
        }
        return transaction {
            UserGroupAssignments.selectAll()
                .where { UserGroupAssignments.id eq createdId }
                .first()
                .toAssignmentResponse()
        }
    }

    fun deleteAssignment(targetId: Uuid, assignmentId: Uuid) {
        val deleted = transaction {
            val exists = UserGroupAssignments.selectAll()
                .where {
                    (UserGroupAssignments.id eq assignmentId) and
                            (UserGroupAssignments.userId eq targetId)
                }
                .firstOrNull() != null
            if (!exists) return@transaction false
            UserGroupAssignments.deleteWhere {
                (UserGroupAssignments.id eq assignmentId) and
                        (UserGroupAssignments.userId eq targetId)
            }
            true
        }
        if (!deleted) throw NotFoundException("Assignment not found")
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toAssignmentResponse() = AssignmentResponse(
    id = this[UserGroupAssignments.id].toString(),
    groupId = this[UserGroupAssignments.groupId].toString(),
    scopeType = ScopeType.valueOf(this[UserGroupAssignments.scopeType]),
    scopeId = this[UserGroupAssignments.scopeId]?.toString(),
)
