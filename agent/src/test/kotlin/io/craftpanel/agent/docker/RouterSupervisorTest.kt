package io.craftpanel.agent.docker

import io.craftpanel.agent.runtime.OutboundSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking

class RouterSupervisorTest :
    FunSpec({

        test("publishes the router status on the first provision, including an available update") {
            val provisioner = mockk<McRouterProvisioner>(relaxed = true)
            every { provisioner.ensureRunning() } returns false
            every { provisioner.updateAvailable } returns true
            val out = mockk<OutboundSink>(relaxed = true)
            val supervisor = RouterSupervisor(provisioner, mockk(relaxed = true), enabled = true, out = out)

            runBlocking { supervisor.ensureReady() }

            supervisor.isRunning.shouldBeTrue()
            supervisor.updateAvailable.shouldBeTrue()
            verify { out.tryRouterStatus(true, true) }
        }

        test("recreateNow delegates to the provisioner and re-publishes the cleared status") {
            val provisioner = mockk<McRouterProvisioner>(relaxed = true)
            every { provisioner.recreateNow() } returns true
            every { provisioner.updateAvailable } returns false
            val network = mockk<NetworkManager>(relaxed = true)
            val out = mockk<OutboundSink>(relaxed = true)
            val supervisor = RouterSupervisor(provisioner, network, enabled = true, out = out)
            // Seed the "update available" state so recreateNow must clear it.
            every { provisioner.ensureRunning() } returns false
            every { provisioner.updateAvailable } returns true
            runBlocking { supervisor.ensureReady() }

            every { provisioner.updateAvailable } returns false
            runBlocking { supervisor.recreateNow() }

            verify { provisioner.recreateNow() }
            verify { network.reconcileRouterAttachments() }
            supervisor.updateAvailable.shouldBeFalse()
            verify { out.tryRouterStatus(true, false) }
        }

        test("disabled management reports running but never an update") {
            val provisioner = mockk<McRouterProvisioner>(relaxed = true)
            every { provisioner.updateAvailable } returns true
            val supervisor = RouterSupervisor(provisioner, mockk(relaxed = true), enabled = false)

            runBlocking { supervisor.ensureReady() }

            supervisor.isRunning.shouldBeFalse()
            supervisor.updateAvailable.shouldBeFalse()
            verify(exactly = 0) { provisioner.ensureRunning() }
        }
    })
