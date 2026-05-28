package io.craftpanel.master.service

import io.craftpanel.master.database.schema.*
import io.craftpanel.master.util.toKotlinUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

@Serializable
data class AssignmentResponse(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("scope_type") val scopeType: String,
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

    fun listAssignments(targetId: UUID): AssignmentsListResponse {
        val exists = transaction {
            Users.selectAll()
                .where { Users.id eq targetId.toKotlinUuid() }
                .firstOrNull() != null
        }
        if (!exists) throw NotFoundException("User not found")
        val assignments = transaction {
            UserGroupAssignments.selectAll()
                .where { UserGroupAssignments.userId eq targetId.toKotlinUuid() }
                .map { it.toAssignmentResponse() }
        }
        return AssignmentsListResponse(assignments)
    }

    fun createAssignment(targetId: UUID, req: CreateAssignmentRequest): AssignmentResponse {
        if (req.scopeType !in setOf("GLOBAL", "SERVER", "NETWORK"))
            throw UnprocessableException("scope_type must be GLOBAL, SERVER, or NETWORK")
        if (req.scopeType != "GLOBAL" && req.scopeId == null)
            throw UnprocessableException("scope_id required for ${req.scopeType} scope")

        val groupId = runCatching { UUID.fromString(req.groupId) }.getOrNull()
            ?: throw UnprocessableException("Invalid group_id")
        val scopeId = req.scopeId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: throw UnprocessableException("Invalid scope_id")
        }

        val validation = transaction {
            val userExists = Users.selectAll()
                .where { Users.id eq targetId.toKotlinUuid() }
                .firstOrNull() != null
            val groupExists = Groups.selectAll()
                .where { Groups.id eq groupId.toKotlinUuid() }
                .firstOrNull() != null
            val scopeExists = when {
                scopeId == null            -> true
                req.scopeType == "SERVER"  -> Servers.selectAll()
                    .where { Servers.id eq scopeId.toKotlinUuid() }
                    .firstOrNull() != null

                req.scopeType == "NETWORK" -> ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq scopeId.toKotlinUuid() }
                    .firstOrNull() != null

                else                       -> true
            }
            val alreadyExists = UserGroupAssignments.selectAll()
                .where {
                    (UserGroupAssignments.userId eq targetId.toKotlinUuid()) and
                            (UserGroupAssignments.groupId eq groupId.toKotlinUuid()) and
                            (UserGroupAssignments.scopeType eq req.scopeType) and
                            if (scopeId != null) (UserGroupAssignments.scopeId eq scopeId.toKotlinUuid()) else (UserGroupAssignments.scopeId.isNull())
                }
                .firstOrNull() != null
            Triple(userExists && groupExists && scopeExists, alreadyExists, userExists && groupExists)
        }

        if (!validation.third) throw NotFoundException("User or group not found")
        if (!validation.first) throw NotFoundException("Scope target not found")
        if (validation.second) throw ConflictException("Assignment already exists")

        val createdId = transaction {
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = targetId.toKotlinUuid()
                it[UserGroupAssignments.groupId] = groupId.toKotlinUuid()
                it[UserGroupAssignments.scopeType] = req.scopeType
                it[UserGroupAssignments.scopeId] = scopeId?.toKotlinUuid()
            }[UserGroupAssignments.id]
        }
        return transaction {
            UserGroupAssignments.selectAll()
                .where { UserGroupAssignments.id eq createdId }
                .first()
                .toAssignmentResponse()
        }
    }

    fun deleteAssignment(targetId: UUID, assignmentId: UUID) {
        val deleted = transaction {
            val exists = UserGroupAssignments.selectAll()
                .where {
                    (UserGroupAssignments.id eq assignmentId.toKotlinUuid()) and
                            (UserGroupAssignments.userId eq targetId.toKotlinUuid())
                }
                .firstOrNull() != null
            if (!exists) return@transaction false
            UserGroupAssignments.deleteWhere {
                (UserGroupAssignments.id eq assignmentId.toKotlinUuid()) and
                        (UserGroupAssignments.userId eq targetId.toKotlinUuid())
            }
            true
        }
        if (!deleted) throw NotFoundException("Assignment not found")
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toAssignmentResponse() = AssignmentResponse(
    id = this[UserGroupAssignments.id].toString(),
    groupId = this[UserGroupAssignments.groupId].toString(),
    scopeType = this[UserGroupAssignments.scopeType],
    scopeId = this[UserGroupAssignments.scopeId]?.toString(),
)
