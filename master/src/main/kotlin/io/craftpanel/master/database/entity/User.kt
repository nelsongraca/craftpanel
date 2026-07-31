package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.repo.UserRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class User(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<User>(Users)

    var username by Users.username
    var email by Users.email
    var passwordHash by Users.passwordHash
    var isActive by Users.isActive
    var createdAt by Users.createdAt

    fun toUserRow() = UserRow(
        id = id.value,
        username = username,
        email = email,
        isActive = isActive,
        createdAt = createdAt.toUtcString()
    )
}
