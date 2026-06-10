package craftpanel.systemtest.admin

import craftpanel.systemtest.harness.ADMIN_EMAIL
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.client.model.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class AdminTest : BaseSystemTest() {

    private lateinit var assignUserId: String
    private lateinit var assignGroupId: String
    private lateinit var assignmentId: String

    init {
        beforeSpec {
            val user = api.createUser(
                CreateUserRequest(
                    username = "assign-test-${System.currentTimeMillis()}",
                    email = "assign-test-${System.currentTimeMillis()}@test.com",
                    password = "test-password-789"
                )
            )
            assignUserId = user.id
            val group = api.createGroup(
                CreateGroupRequest(name = "assign-group-${System.currentTimeMillis()}")
            )
            assignGroupId = group.id
        }

        afterSpec {
            if (::assignUserId.isInitialized) runCatching { api.deleteUser(assignUserId) }
            if (::assignGroupId.isInitialized) runCatching { api.deleteGroup(assignGroupId) }
        }

        context("User management") {

            lateinit var createdUserId: String
            val testEmail = "testuser-${System.currentTimeMillis()}@test.com"
            val testUsername = "testuser-${System.currentTimeMillis()}"

            should("creates a new user") {
                val user = api.createUser(
                    CreateUserRequest(username = testUsername, email = testEmail, password = "test-password-123")
                )
                createdUserId = user.id
                user.email shouldBe testEmail
                user.username shouldBe testUsername
                user.isActive shouldBe true
            }

            should("lists users including the new user") {
                val users = api.listUsers()
                users.users.map { it.email } shouldContain testEmail
            }

            should("gets user by ID") {
                val user = api.getUser(createdUserId)
                user.email shouldBe testEmail
                user.username shouldBe testUsername
                user.isActive shouldBe true
            }

            should("updates user email and username") {
                val newEmail = "updated-${System.currentTimeMillis()}@test.com"
                val newUsername = "updated-${System.currentTimeMillis()}"
                val updated = api.updateUser(
                    createdUserId,
                    PatchUserRequest(username = newUsername, email = newEmail)
                )
                updated.email shouldBe newEmail
                updated.username shouldBe newUsername
            }

            should("deactivates a user") {
                val updated = api.updateUser(
                    createdUserId,
                    PatchUserRequest(isActive = false)
                )
                updated.isActive shouldBe false
            }

            should("deleted user no longer appears in list") {
                api.deleteUser(createdUserId)
                val users = api.listUsers()
                users.users.map { it.email } shouldNotContain testEmail
            }

            should("creating a user with duplicate email returns 409") {
                val ex = shouldThrow<ClientException> {
                    api.createUser(
                        CreateUserRequest(
                            username = "duplicate-${System.currentTimeMillis()}",
                            email = ADMIN_EMAIL,
                            password = "test-password-456"
                        )
                    )
                }
                ex.statusCode shouldBe 409
            }

            should("cannot delete self") {
                val me = api.authMe()
                val ex = shouldThrow<ClientException> {
                    api.deleteUser(me.id)
                }
                ex.statusCode shouldBe 409
            }

            should("getting a non-existent user returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.getUser("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }
        }

        context("Group management") {

            lateinit var createdGroupId: String
            val groupName = "test-group-${System.currentTimeMillis()}"

            should("creates a new group") {
                val group = api.createGroup(CreateGroupRequest(name = groupName))
                createdGroupId = group.id
                group.name shouldBe groupName
                group.isSystem shouldBe false
                group.permissions shouldBe emptyList()
            }

            should("lists groups including the new group") {
                val groups = api.listGroups()
                groups.map { it.name } shouldContain groupName
            }

            should("gets group by ID") {
                val group = api.getGroup(createdGroupId)
                group.name shouldBe groupName
                group.isSystem shouldBe false
            }

            should("updates group name") {
                val newName = "renamed-group-${System.currentTimeMillis()}"
                val updated = api.updateGroup(createdGroupId, PatchGroupRequest(name = newName))
                updated.name shouldBe newName
            }

            should("sets group permissions") {
                val updated = api.setGroupPermissions(
                    createdGroupId,
                    PutGroupPermissionsRequest(permissions = listOf("server.view", "server.console"))
                )
                updated.permissions shouldBe listOf("server.view", "server.console")
            }

            should("deletes a custom group") {
                api.deleteGroup(createdGroupId)
                val groups = api.listGroups()
                groups.map { it.id } shouldNotContain createdGroupId
            }

            should("cannot delete a system group") {
                val groups = api.listGroups()
                val superAdmin = groups.first { it.isSystem && it.name == "Super Admin" }
                val ex = shouldThrow<ClientException> {
                    api.deleteGroup(superAdmin.id)
                }
                ex.statusCode shouldBe 409
            }

            should("creating a group with duplicate name returns 409") {
                val ex = shouldThrow<ClientException> {
                    api.createGroup(CreateGroupRequest(name = "Viewer"))
                }
                ex.statusCode shouldBe 409
            }
        }

        context("Group assignments") {

            should("creates a GLOBAL assignment") {
                val assignment = api.createAssignment(
                    assignUserId,
                    CreateAssignmentRequest(
                        groupId = assignGroupId,
                        scopeType = "GLOBAL"
                    )
                )
                assignmentId = assignment.id
                assignment.groupId shouldBe assignGroupId
                assignment.scopeType shouldBe "GLOBAL"
            }

            should("lists assignments for user") {
                val assignments = api.listUserAssignments(assignUserId)
                assignments.assignments.map { it.id } shouldContain assignmentId
            }

            should("deletes an assignment") {
                api.deleteAssignment(assignUserId, assignmentId)
                val assignments = api.listUserAssignments(assignUserId)
                assignments.assignments.map { it.id } shouldNotContain assignmentId
            }
        }
    }
}
