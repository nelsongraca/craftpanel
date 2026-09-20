package io.craftpanel.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuildInfoTest :
    FunSpec({

        test("exposes a non-blank version baked into the artifact") {
            (BuildInfo.version.isNotBlank()) shouldBe true
        }
    })
