package craftpanel.systemtest.node

import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Tags
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldNotBeBlank

@Tags("Node")
class NodeIpTest : BaseSystemTest() {

    init {
        context("getMyIp") {

            should("returns a non-blank string") {
                val ip = api.getMyIp()
                ip.shouldNotBeBlank()
            }

            should("returns a value that looks like an IP address") {
                val ip = api.getMyIp()
                (ip.contains('.') || ip.contains(':')).shouldBeTrue()
            }
        }
    }
}
