package io.craftpanel.master.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

class ServerProvisioningDefaultsTest :
    FunSpec({
        test("new servers default to MeowIce JVM flags, not Aikar's") {
            val vars = buildDefaultEnvVars("1.21.4", "Vanilla", "CraftPanel")

            vars["USE_MEOWICE_FLAGS"] shouldBe "true"
            vars shouldNotContainKey "USE_AIKAR_FLAGS"
        }

        test("defaults still carry the core server properties") {
            val vars = buildDefaultEnvVars("1.21.4", "Vanilla", "CraftPanel")

            vars shouldContainKey "MOTD"
            vars["ONLINE_MODE"] shouldBe "true"
        }
    })
