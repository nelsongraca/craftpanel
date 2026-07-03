package io.craftpanel.master.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PermissionResolverTest : FunSpec({

    test("grants returns true for wildcard *") {
        PermissionResolver.grants("*", Permission.SERVER_VIEW) shouldBe true
    }

    test("grants returns true for prefix wildcard server.*") {
        PermissionResolver.grants("server.*", Permission.SERVER_VIEW) shouldBe true
    }

    test("grants returns true for exact match") {
        PermissionResolver.grants(Permission.SERVER_VIEW.node, Permission.SERVER_VIEW) shouldBe true
    }

    test("grants returns false for non-matching permission") {
        PermissionResolver.grants(Permission.SERVER_BACKUP.node, Permission.SERVER_VIEW) shouldBe false
    }

    test("grants returns false for unrelated wildcard prefix") {
        PermissionResolver.grants("system.*", Permission.SERVER_VIEW) shouldBe false
    }
})
