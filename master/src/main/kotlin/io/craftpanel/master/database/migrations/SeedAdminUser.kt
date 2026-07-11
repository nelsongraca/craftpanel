package io.craftpanel.master.database.migrations

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.schema.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SeedAdminUser")

fun seedAdminUser(email: String, password: String, username: String = "admin") {
    val anyUser = Users.selectAll()
        .firstOrNull()
    if (anyUser != null) return

    logger.info("No users found — seeding initial admin user: $email")

    val hash = Argon2Hasher.hash(password)
    val userId = Users.insert {
        it[Users.username] = username
        it[Users.email] = email
        it[Users.passwordHash] = hash
    }[Users.id]

    val superAdminGroupId = Groups.selectAll()
        .where { Groups.name eq "Super Admin" }
        .first()[Groups.id]

    UserGroupAssignments.insert {
        it[UserGroupAssignments.userId] = userId
        it[UserGroupAssignments.groupId] = superAdminGroupId
        it[UserGroupAssignments.scopeType] = ScopeType.GLOBAL.name
        it[UserGroupAssignments.scopeId] = null
    }

    logger.info("Admin user seeded successfully")
}
