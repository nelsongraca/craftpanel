package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.command.InspectContainerCmd
import com.github.dockerjava.api.command.InspectContainerResponse
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
            val goodEnv = listOf("IN_DOCKER=true", "DYNAMIC_PROXY_PROTOCOL=true")

            test("no drift when env, socket group and image all match") {
                routerContainerDrift(goodEnv, listOf("48"), "router:1", "router:1", "48") shouldBe emptyList()
            }

            test("no socket-group drift when the socket GID cannot be determined") {
                routerContainerDrift(goodEnv, null, "router:1", "router:1", null) shouldBe emptyList()
            }

            test("flags missing IN_DOCKER=true") {
                routerContainerDrift(
                    listOf("DYNAMIC_PROXY_PROTOCOL=true"),
                    listOf("48"),
                    "router:1",
                    "router:1",
                    "48"
                ) shouldBe listOf("autoDiscovery")
            }

            test("flags missing DYNAMIC_PROXY_PROTOCOL=true") {
                routerContainerDrift(
                    listOf("IN_DOCKER=true"),
                    listOf("48"),
                    "router:1",
                    "router:1",
                    "48"
                ) shouldBe listOf("dynamicProxyProtocol")
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
                    listOf("autoDiscovery", "dynamicProxyProtocol", "socketGroup", "image")
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
                every { containerConfig.env } returns arrayOf("IN_DOCKER=true", "DYNAMIC_PROXY_PROTOCOL=true")
                every { existing.hostConfig } returns hostConfig
                every { hostConfig.groupAdd } returns null
                every { existing.state } returns containerState
                every { containerState.running } returns true

                every { docker.removeContainerCmd("old-id") } returns mockk<RemoveContainerCmd>(relaxed = true)
                every { docker.inspectImageCmd("new-router:2") } throws NotFoundException("absent")
                val pullCmd = mockk<PullImageCmd>(relaxed = true)
                val pullCallback = mockk<PullImageResultCallback>(relaxed = true)
                every { docker.pullImageCmd("new-router:2") } returns pullCmd
                every { pullCmd.exec(any<PullImageResultCallback>()) } returns pullCallback
                every { docker.createContainerCmd("new-router:2") } returns mockk<CreateContainerCmd>(relaxed = true)
                every { docker.startContainerCmd(any<String>()) } returns mockk<StartContainerCmd>(relaxed = true)

                McRouterProvisioner(docker, "new-router:2", updateOnStart = false).ensureRunning()

                verify { docker.removeContainerCmd("old-id") }
                verify { docker.pullImageCmd("new-router:2") }
                verify { docker.createContainerCmd("new-router:2") }
            }
        }
    })
