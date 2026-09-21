package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.fakeServerView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.uuid.Uuid

class ProxyPatchWriterTest :
    FunSpec({
        val patchService = mockk<ProxyConfigPatchService>()

        beforeTest { clearMocks(patchService) }

        test("write sends the patch under the craftpanel-patch.json filename") {
            val server = fakeServerView(serverType = ServerType.VELOCITY)
            every { patchService.generatePatch(server.id) } returns "{\"motd\":\"hi\"}"
            var written: Triple<Uuid, String, ByteArray>? = null
            val writer = ProxyPatchWriter(patchService) { id, name, bytes -> written = Triple(id, name, bytes) }
            runTest { writer.write(server) }

            written?.first shouldBe server.id
            written?.second shouldBe "craftpanel-patch.json"
            written?.third?.toString(Charsets.UTF_8) shouldBe "{\"motd\":\"hi\"}"
        }

        test("write is a no-op for a non-proxy server") {
            val server = fakeServerView(serverType = ServerType.VANILLA)
            var called = false
            val writer = ProxyPatchWriter(patchService) { _, _, _ -> called = true }
            runTest { writer.write(server) }

            called shouldBe false
            verify(exactly = 0) { patchService.generatePatch(any<Uuid>()) }
        }

        test("write is a no-op when there is no patch") {
            val server = fakeServerView(serverType = ServerType.VELOCITY)
            every { patchService.generatePatch(server.id) } returns null
            var called = false
            val writer = ProxyPatchWriter(patchService) { _, _, _ -> called = true }
            runTest { writer.write(server) }

            called shouldBe false
        }

        test("writeIfRunning skips when the server is not HEALTHY") {
            val server = fakeServerView(serverType = ServerType.VELOCITY, status = "STOPPED")
            val writer = ProxyPatchWriter(patchService) { _, _, _ -> }
            runTest { writer.writeIfRunning(server) }

            verify(exactly = 0) { patchService.generatePatch(any<Uuid>()) }
        }

        test("writeIfRunning writes when the server is HEALTHY") {
            val server = fakeServerView(serverType = ServerType.VELOCITY, status = "HEALTHY")
            every { patchService.generatePatch(server.id) } returns "{}"
            var called = false
            val writer = ProxyPatchWriter(patchService) { _, _, _ -> called = true }
            runTest { writer.writeIfRunning(server) }

            called shouldBe true
        }
    })
