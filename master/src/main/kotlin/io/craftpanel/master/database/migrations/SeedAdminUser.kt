package io.craftpanel.master.database.migrations

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.schema.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
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

fun resetSeedAdminPassword(email: String, newPassword: String): Boolean {
    val admin = Users.selectAll()
        .where { Users.email eq email }
        .firstOrNull()

    if (admin == null) {
        logger.warn("ADMIN_SEED_RESET set but no user found with email: $email")
        return false
    }

    val userId = admin[Users.id].value
    val hash = Argon2Hasher.hash(newPassword)

    Users.update({ Users.id eq userId }) {
        it[passwordHash] = hash
        it[mustChangePassword] = true
    }
    RefreshTokens.deleteWhere { RefreshTokens.userId eq userId }

    logger.info("Admin password reset via adminSeed.resetPassword — user must change it on next login")
    return true
}
