package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.command.InspectContainerCmd
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.InspectImageCmd
import com.github.dockerjava.api.command.PullImageCmd
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.command.RemoveContainerCmd
import com.github.dockerjava.api.command.StartContainerCmd
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.ContainerConfig
import com.github.dockerjava.api.model.HostConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class McRouterProvisionerTest :
    FunSpec({

        context("routerContainerDrift") {
            val goodEnv = listOf("IN_DOCKER=true", "DYNAMIC_PROXY_PROTOCOL=true", "LOG_LEVEL=warn")

            test("no drift when env, socket group and image all match") {
                routerContainerDrift(goodEnv, listOf("48"), "router:1", "router:1", "48") shouldBe emptyList()
            }

            test("no socket-group drift when the socket GID cannot be determined") {
                routerContainerDrift(goodEnv, null, "router:1", "router:1", null) shouldBe emptyList()
            }

            test("flags missing IN_DOCKER=true") {
                routerContainerDrift(
                    listOf("DYNAMIC_PROXY_PROTOCOL=true", "LOG_LEVEL=warn"),
                    listOf("48"),
                    "router:1",
                    "router:1",
                    "48"
                ) shouldBe listOf("autoDiscovery")
            }

            test("flags missing DYNAMIC_PROXY_PROTOCOL=true") {
                routerContainerDrift(
                    listOf("IN_DOCKER=true", "LOG_LEVEL=warn"),
                    listOf("48"),
                    "router:1",
                    "router:1",
                    "48"
                ) shouldBe listOf("dynamicProxyProtocol")
            }

            test("flags missing LOG_LEVEL=warn") {
                routerContainerDrift(
                    listOf("IN_DOCKER=true", "DYNAMIC_PROXY_PROTOCOL=true"),
                    listOf("48"),
                    "router:1",
                    "router:1",
                    "48"
                ) shouldBe listOf("logLevel")
            }

            test("flags a non-default configured log level") {
                routerContainerDrift(goodEnv, listOf("48"), "router:1", "router:1", "48", "debug") shouldBe
                    listOf("logLevel")
            }

            test("no log-level drift when a non-default level matches") {
                routerContainerDrift(
                    listOf("IN_DOCKER=true", "DYNAMIC_PROXY_PROTOCOL=true", "LOG_LEVEL=debug"),
                    listOf("48"),
                    "router:1",
                    "router:1",
                    "48",
                    "debug"
                ) shouldBe emptyList()
            }

            test("flags a socket GID missing from group_add") {
                routerContainerDrift(goodEnv, listOf("999"), "router:1", "router:1", "48") shouldBe
                    listOf("socketGroup")
            }

            test("flags a different configured image") {
                routerContainerDrift(goodEnv, listOf("48"), "old-router:1", "new-router:2", "48") shouldBe
                    listOf("image")
            }

            test("reports every failing check") {
                routerContainerDrift(emptyList(), null, null, "new-router:2", "48") shouldBe
                    listOf("autoDiscovery", "dynamicProxyProtocol", "logLevel", "socketGroup", "image")
            }
        }

        context("routerImageUpdateAvailable") {
            test("true only when both ids are known and differ") {
                routerImageUpdateAvailable("sha256:old", "sha256:new") shouldBe true
                routerImageUpdateAvailable("sha256:same", "sha256:same") shouldBe false
                routerImageUpdateAvailable(null, "sha256:new") shouldBe false
                routerImageUpdateAvailable("sha256:old", null) shouldBe false
                routerImageUpdateAvailable("", "sha256:new") shouldBe false
            }
        }

        context("ensureRunning") {
            test("recreates, pulls and creates with the new image when the existing container runs a different image") {
                val docker = mockk<DockerClient>()
                val existing = mockk<InspectContainerResponse>()
                val containerConfig = mockk<ContainerConfig>()
                val containerState = mockk<InspectContainerResponse.ContainerState>()
                val hostConfig = mockk<HostConfig>()

                val inspectCmd = mockk<InspectContainerCmd>(relaxed = true)
                every { inspectCmd.exec() } returns existing
                every { docker.inspectContainerCmd("craftpanel-mc-router") } returns inspectCmd
                every { existing.id } returns "old-id"
                every { existing.config } returns containerConfig
                every { containerConfig.image } returns "old-router:1"
                every { containerConfig.env } returns arrayOf(
                    "IN_DOCKER=true",
                    "DYNAMIC_PROXY_PROTOCOL=true",
                    "LOG_LEVEL=warn"
                )
                every { existing.hostConfig } returns hostConfig
                every { hostConfig.groupAdd } returns null
                every { existing.state } returns containerState
                every { containerState.running } returns true

                every { docker.removeContainerCmd("old-id") } returns mockk<RemoveContainerCmd>(relaxed = true)
                every { docker.inspectImageCmd("new-router:2") } throws NotFoundException("absent")
                val pullCmd = mockk<PullImageCmd>(relaxed = true)
                val pullCallback = mockk<PullImageResultCallback>(relaxed = true)
                val createCmd = mockk<CreateContainerCmd>(relaxed = true)
                every { docker.pullImageCmd("new-router:2") } returns pullCmd
                every { pullCmd.exec(any<PullImageResultCallback>()) } returns pullCallback
                every { docker.createContainerCmd("new-router:2") } returns createCmd
                every { createCmd.withName(any()) } returns createCmd
                every { createCmd.withExposedPorts(*anyVararg()) } returns createCmd
                every { createCmd.withEnv(*anyVararg()) } returns createCmd
                every { createCmd.withHostConfig(any()) } returns createCmd
                every { createCmd.withLabels(any()) } returns createCmd
                every { docker.startContainerCmd(any<String>()) } returns mockk<StartContainerCmd>(relaxed = true)

                McRouterProvisioner(docker, { "new-router:2" }, updateOnStart = false).ensureRunning()

                verify { docker.removeContainerCmd("old-id") }
                verify { docker.pullImageCmd("new-router:2") }
                verify { docker.createContainerCmd("new-router:2") }
                verify { createCmd.withEnv("IN_DOCKER=true", "DYNAMIC_PROXY_PROTOCOL=true", "LOG_LEVEL=warn") }
            }

            test("a running router with a changed image id is left alone and reports an update") {
                val docker = mockk<DockerClient>()
                val existing = mockk<InspectContainerResponse>()
                val containerConfig = mockk<ContainerConfig>()
                val containerState = mockk<InspectContainerResponse.ContainerState>()
                val hostConfig = mockk<HostConfig>()

                val inspectCmd = mockk<InspectContainerCmd>(relaxed = true)
                every { inspectCmd.exec() } returns existing
                every { docker.inspectContainerCmd("craftpanel-mc-router") } returns inspectCmd
                every { existing.id } returns "id"
                every { existing.imageId } returns "sha256:old"
                every { existing.config } returns containerConfig
                every { containerConfig.image } returns "router:1"
                every { containerConfig.env } returns arrayOf(
                    "IN_DOCKER=true",
                    "DYNAMIC_PROXY_PROTOCOL=true",
                    "LOG_LEVEL=warn"
                )
                every { existing.hostConfig } returns hostConfig
                every { hostConfig.groupAdd } returns listOf("48")
                every { existing.state } returns containerState
                every { containerState.running } returns true
                val imageCmd = mockk<InspectImageCmd>(relaxed = true)
                every { imageCmd.exec().id } returns "sha256:new"
                every { docker.inspectImageCmd("router:1") } returns imageCmd

                val provisioner = McRouterProvisioner(
                    docker,
                    { "router:1" },
                    updateOnStart = false,
                    socketGidProvider = { "48" }
                )

                provisioner.ensureRunning() shouldBe false
                provisioner.updateAvailable shouldBe true
                verify(exactly = 0) { docker.removeContainerCmd(any<String>()) }
                verify(exactly = 0) { docker.pullImageCmd(any<String>()) }
            }

            test("a running router on the current image reports no update") {
                val docker = mockk<DockerClient>()
                val existing = mockk<InspectContainerResponse>()
                val containerConfig = mockk<ContainerConfig>()
                val containerState = mockk<InspectContainerResponse.ContainerState>()
                val hostConfig = mockk<HostConfig>()

                val inspectCmd = mockk<InspectContainerCmd>(relaxed = true)
                every { inspectCmd.exec() } returns existing
                every { docker.inspectContainerCmd("craftpanel-mc-router") } returns inspectCmd
                every { existing.id } returns "id"
                every { existing.imageId } returns "sha256:same"
                every { existing.config } returns containerConfig
                every { containerConfig.image } returns "router:1"
                every { containerConfig.env } returns arrayOf(
                    "IN_DOCKER=true",
                    "DYNAMIC_PROXY_PROTOCOL=true",
                    "LOG_LEVEL=warn"
                )
                every { existing.hostConfig } returns hostConfig
                every { hostConfig.groupAdd } returns listOf("48")
                every { existing.state } returns containerState
                every { containerState.running } returns true
                val imageCmd = mockk<InspectImageCmd>(relaxed = true)
                every { imageCmd.exec().id } returns "sha256:same"
                every { docker.inspectImageCmd("router:1") } returns imageCmd

                val provisioner = McRouterProvisioner(
                    docker,
                    { "router:1" },
                    updateOnStart = false,
                    socketGidProvider = { "48" }
                )

                provisioner.ensureRunning() shouldBe false
                provisioner.updateAvailable shouldBe false
                verify(exactly = 0) { docker.removeContainerCmd(any<String>()) }
            }
        }
    })
