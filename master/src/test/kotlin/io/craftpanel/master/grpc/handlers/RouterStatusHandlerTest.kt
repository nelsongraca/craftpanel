package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.service.RouterStatus
import io.craftpanel.master.service.RouterStatusStore
import io.craftpanel.proto.agentMessage
import io.craftpanel.proto.nodeStateSnapshot
import io.craftpanel.proto.routerStatusUpdate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class RouterStatusHandlerTest :
    FunSpec({

        test("a live router_status update is recorded") {
            val store = RouterStatusStore()
            val handler = RouterStatusHandler(store)

            handler.handle(
                agentMessage {
                    nodeId = "node-1"
                    routerStatus = routerStatusUpdate {
                        running = true
                        updateAvailable = true
                    }
                },
                "node-1"
            )

            store.get("node-1")!!.running.shouldBeTrue()
            store.get("node-1")!!.updateAvailable.shouldBeTrue()
        }

        test("the opening node-state snapshot seeds the router update flag") {
            val store = RouterStatusStore()
            val handler = RouterStatusHandler(store)

            handler.handleSnapshot(
                nodeStateSnapshot {
                    routerRunning = true
                    routerUpdateAvailable = true
                },
                "node-2"
            )

            store.get("node-2")!!.updateAvailable.shouldBeTrue()
        }

        test("clear drops the node's status") {
            val store = RouterStatusStore()
            store.update("node-3", RouterStatus(running = true, updateAvailable = true))

            RouterStatusHandler(store).clear("node-3")

            store.get("node-3") shouldBe null
        }
    })
