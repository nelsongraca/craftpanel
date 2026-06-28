package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.service.repo.AssignmentRow
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

class AssignmentService(
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository,
    private val serverRepository: ServerRepository,
    private val networkRepository: NetworkRepository,
) {

    fun listAssignments(targetId: Uuid): AssignmentsListResponse {
        userRepository.findById(targetId) ?: throw NotFoundException("User not found")
        val assignments = userRepository.listAssignments(targetId)
            .map { it.toResponse() }
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

        userRepository.findById(targetId) ?: throw NotFoundException("User or group not found")
        groupRepository.findById(groupId) ?: throw NotFoundException("User or group not found")

        if (scopeId != null) {
            val scopeExists = when (req.scopeType) {
                ScopeType.SERVER.name  -> serverRepository.findById(scopeId) != null
                ScopeType.NETWORK.name -> networkRepository.findById(scopeId) != null
                else                   -> true
            }
            if (!scopeExists) throw NotFoundException("Scope target not found")
        }

        val exists = userRepository.findAssignment(targetId, groupId, req.scopeType, scopeId)
        if (exists != null) throw ConflictException("Assignment already exists")

        return userRepository.createAssignment(targetId, groupId, req.scopeType, scopeId)
            .toResponse()
    }

    fun deleteAssignment(targetId: Uuid, assignmentId: Uuid) {
        val all = userRepository.listAssignments(targetId)
        val assignment = all.firstOrNull { it.id == assignmentId }
            ?: throw NotFoundException("Assignment not found")
        userRepository.deleteAssignment(assignment.id)
    }
}

private fun AssignmentRow.toResponse() = AssignmentResponse(
    id = id.toString(),
    groupId = groupId.toString(),
    scopeType = ScopeType.valueOf(scopeType),
    scopeId = scopeId?.toString(),
)
