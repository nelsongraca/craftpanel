package io.craftpanel.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContainerNamesTest :
    FunSpec({

        val names = ContainerNames(ContainerNames.DEFAULT_PREFIX)

        context("default prefix") {
            test("container") {
                names.container("abc") shouldBe "craftpanel-abc"
            }
            test("shared network") {
                names.sharedNetwork("net-1") shouldBe "craftpanel-net-net-1"
            }
            test("standalone network") {
                names.standaloneNetwork("abc") shouldBe "craftpanel-server-abc"
            }
            test("rsync names") {
                names.rsyncReceive("m1") shouldBe "craftpanel-rsync-recv-m1"
                names.rsyncSend("m1", final = false) shouldBe "craftpanel-rsync-send-m1"
                names.rsyncSend("m1", final = true) shouldBe "craftpanel-rsync-send-m1-final"
            }
            test("serverIdOf round-trips container()") {
                names.serverIdOf(names.container("abc")) shouldBe "abc"
            }
            test("isManagedContainerName") {
                names.isManagedContainerName("craftpanel-abc") shouldBe true
                names.isManagedContainerName("other-abc") shouldBe false
            }
            test("isManagedNetwork covers both per-server families but not globals") {
                names.isManagedNetwork(names.sharedNetwork("net-1")) shouldBe true
                names.isManagedNetwork(names.standaloneNetwork("abc")) shouldBe true
                // host-global network is deliberately not prefix-derived
                names.isManagedNetwork("craftpanel") shouldBe false
                names.isManagedNetwork("bridge") shouldBe false
            }
            test("serverIdOf throws on a name that is not ours") {
                shouldThrow<IllegalArgumentException> { names.serverIdOf("other-abc") }
                shouldThrow<IllegalArgumentException> { names.serverIdOf("craftpanel") }
            }
        }

        // The custom-prefix case is the regression these names exist to prevent: a drifted copy
        // hardcoded "craftpanel-" and leaked the shared network on removal.
        context("custom prefix") {
            val custom = ContainerNames("mypanel")

            test("every name carries the custom prefix") {
                custom.container("abc") shouldBe "mypanel-abc"
                custom.sharedNetwork("net-1") shouldBe "mypanel-net-net-1"
                custom.standaloneNetwork("abc") shouldBe "mypanel-server-abc"
                custom.rsyncReceive("m1") shouldBe "mypanel-rsync-recv-m1"
                custom.rsyncSend("m1", final = true) shouldBe "mypanel-rsync-send-m1-final"
            }

            test("a default-prefixed name is not recognised as managed") {
                custom.isManagedContainerName("craftpanel-abc") shouldBe false
                custom.isManagedNetwork("craftpanel-net-net-1") shouldBe false
            }

            test("round-trip works under the custom prefix") {
                custom.serverIdOf(custom.container("abc")) shouldBe "abc"
            }
        }

        context("prefix normalisation") {
            test("trims whitespace and trailing separators") {
                ContainerNames("  mypanel-  ").container("abc") shouldBe "mypanel-abc"
            }
            test("rejects a blank prefix") {
                shouldThrow<IllegalArgumentException> { ContainerNames("   ") }
                shouldThrow<IllegalArgumentException> { ContainerNames("-") }
            }
        }
    })
