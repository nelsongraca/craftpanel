package io.craftpanel.master.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PortAllocatorTest : FunSpec({

    test("returns first free port in range when none used") {
        val result = PortAllocator.pickFreePort(portRangeStart = 25570, portRangeEnd = 25580, usedPorts = emptySet())

        result shouldBe 25570
    }

    test("skips used ports and returns first free one") {
        val result = PortAllocator.pickFreePort(
            portRangeStart = 25570,
            portRangeEnd = 25580,
            usedPorts = setOf(25570, 25571, 25572),
        )

        result shouldBe 25573
    }

    test("returns null when entire range is used") {
        val result = PortAllocator.pickFreePort(
            portRangeStart = 25570,
            portRangeEnd = 25572,
            usedPorts = setOf(25570, 25571, 25572),
        )

        result.shouldBeNull()
    }
})
