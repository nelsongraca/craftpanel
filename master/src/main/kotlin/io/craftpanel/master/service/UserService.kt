package io.craftpanel.master.service

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.master.service.repo.UserRow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class CreateUserRequest(val username: String, val email: String, val password: String)

@Serializable
data class PatchUserRequest(
    val username: String? = null,
    val email: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    val email: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class UsersListResponse(val users: List<UserResponse>)

class UserService(private val userRepository: UserRepository) {

    fun listUsers(): UsersListResponse =
        UsersListResponse(
            userRepository.listAll()
                .map { it.toResponse() })

    fun createUser(req: CreateUserRequest): UserResponse {
        val hash = Argon2Hasher.hash(req.password)
        val byUsername = userRepository.findByUsername(req.username)
        val byEmail = userRepository.findByEmail(req.email)
        if (byUsername != null || byEmail != null) throw ConflictException("Username or email already taken")
        return userRepository.create(req.username, req.email, hash)
            .toResponse()
    }

    fun getUser(targetId: Uuid): UserResponse =
        userRepository.findById(targetId)
            ?.toResponse() ?: throw NotFoundException("User not found")

    fun updateUser(targetId: Uuid, req: PatchUserRequest): UserResponse {
        userRepository.findById(targetId) ?: throw NotFoundException("User not found")
        if (req.username != null || req.email != null) {
            val byUsername = if (req.username != null) userRepository.findByUsername(req.username) else null
            val byEmail = if (req.email != null) userRepository.findByEmail(req.email) else null
            val conflict = listOfNotNull(byUsername, byEmail).any { it.id != targetId }
            if (conflict) throw UnprocessableException("Username or email already taken")
        }
        userRepository.update(targetId, req.username, req.email, req.isActive)
        return userRepository.findById(targetId)!!
            .toResponse()
    }

    fun deleteUser(targetId: Uuid) {
        userRepository.findById(targetId) ?: throw NotFoundException("User not found")
        userRepository.delete(targetId)
    }
}

private fun UserRow.toResponse() = UserResponse(
    id = id.toString(),
    username = username,
    email = email,
    isActive = isActive,
    createdAt = createdAt,
)
