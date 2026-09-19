package io.craftpanel.agent.docker

import io.craftpanel.proto.StartContainerCommand
import io.craftpanel.proto.extraPortBinding
import io.craftpanel.proto.startContainerCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContainerSpecDiffTest :
    FunSpec({

        val hostRoot = "/hostdata"

        fun spec() = startContainerCommand {
            serverId = "srv-1"
            containerName = "craftpanel-srv-1"
            image = "itzg/minecraft-server:latest"
            hostPort = 25565
            memoryMb = 1024
            cpuLimitMillicores = 256
            envVars.putAll(mapOf("MOTD" to "hi", "PVP" to "true"))
            dockerNetwork = "craftpanel-server-srv-1"
            internalListenPort = 25565
            containerProtocol = "TCP"
            publicHostname = "play.example.com"
            serverName = "survival"
            extraPorts.add(
                extraPortBinding {
                    hostPort = 25580
                    containerPort = 8123
                    protocol = "TCP"
                }
            )
        }

        fun snapshot() = ContainerSnapshot(
            image = "itzg/minecraft-server:latest",
            // extra image-default var proves env is a subset check
            env = mapOf("MOTD" to "hi", "PVP" to "true", "IMAGE_DEFAULT" to "x"),
            binds = listOf(BindSnapshot("/hostdata/servers/srv-1", "/data", false)),
            portBindings = listOf(
                PortBindingSnapshot(25565, "tcp", 25565),
                PortBindingSnapshot(8123, "tcp", 25580)
            ),
            user = "",
            memoryMb = 1024,
            cpuLimitMillicores = 256,
            labels = mapOf("mc-router.host" to "play.example.com"),
            networkMode = "craftpanel-server-srv-1",
            hostname = "survival"
        )

        fun diff(s: StartContainerCommand = spec(), snap: ContainerSnapshot = snapshot()) = ContainerSpecDiff.diff(s, snap, hostRoot)

        test("a satisfying container matches") {
            diff() shouldBe SpecDiff.Match
        }

        test("image differs") {
            diff(snap = snapshot().copy(image = "other:1")) shouldBe SpecDiff.Mismatch(listOf(SpecDiffReason.IMAGE))
        }

        test("user differs") {
            diff(snap = snapshot().copy(user = "1000")) shouldBe SpecDiff.Mismatch(listOf(SpecDiffReason.USER))
        }

        test("memory differs") {
            diff(snap = snapshot().copy(memoryMb = 2048)) shouldBe SpecDiff.Mismatch(listOf(SpecDiffReason.MEMORY))
        }

        test("cpu shares differ") {
            diff(snap = snapshot().copy(cpuLimitMillicores = 0)) shouldBe SpecDiff.Mismatch(listOf(SpecDiffReason.CPU))
        }

        test("a configured env var with a different value") {
            diff(snap = snapshot().copy(env = snapshot().env + ("MOTD" to "bye"))) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.ENV))
        }

        test("a configured env var missing from the container") {
            diff(snap = snapshot().copy(env = mapOf("PVP" to "true"))) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.ENV))
        }

        test("a bind mounted somewhere else") {
            diff(snap = snapshot().copy(binds = listOf(BindSnapshot("/other/servers/srv-1", "/data", false)))) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.BIND))
        }

        test("a data-dir override changes the expected bind path") {
            // Container is still mounted at the id path, spec now expects the override.
            diff(s = spec().toBuilder().setDataDirName("survival").build()) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.BIND))
        }

        test("a data-dir override matches when the container is bound to it") {
            diff(
                s = spec().toBuilder().setDataDirName("survival").build(),
                snap = snapshot().copy(binds = listOf(BindSnapshot("/hostdata/servers/survival", "/data", false)))
            ) shouldBe SpecDiff.Match
        }

        test("a read-only data bind") {
            diff(snap = snapshot().copy(binds = listOf(BindSnapshot("/hostdata/servers/srv-1", "/data", true)))) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.BIND))
        }

        test("a different data container path") {
            diff(s = spec().toBuilder().setDataContainerPath("/server").build()) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.BIND))
        }

        test("a different primary host port") {
            diff(
                snap = snapshot().copy(
                    portBindings = listOf(
                        PortBindingSnapshot(25565, "tcp", 25566),
                        PortBindingSnapshot(8123, "tcp", 25580)
                    )
                )
            ) shouldBe SpecDiff.Mismatch(listOf(SpecDiffReason.PORTS))
        }

        test("a missing extra port") {
            diff(snap = snapshot().copy(portBindings = listOf(PortBindingSnapshot(25565, "tcp", 25565)))) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.PORTS))
        }

        test("port bindings compare order-independently") {
            diff(snap = snapshot().copy(portBindings = snapshot().portBindings.reversed())) shouldBe SpecDiff.Match
        }

        test("mc-router hostname label differs") {
            diff(snap = snapshot().copy(labels = emptyMap())) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.HOSTNAME_LABEL))
        }

        test("no public hostname means the mc-router label is not checked") {
            diff(s = spec().toBuilder().setPublicHostname("").build(), snap = snapshot().copy(labels = emptyMap())) shouldBe
                SpecDiff.Match
        }

        test("network mode differs") {
            diff(snap = snapshot().copy(networkMode = "bridge")) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.NETWORK_MODE))
        }

        test("no docker network means the network mode is not checked") {
            diff(s = spec().toBuilder().setDockerNetwork("").build(), snap = snapshot().copy(networkMode = "bridge")) shouldBe
                SpecDiff.Match
        }

        test("hostname differs from the server name") {
            diff(snap = snapshot().copy(hostname = "9f8e7d6c5b4a")) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.HOSTNAME))
        }

        test("no server name means the hostname is not checked") {
            diff(s = spec().toBuilder().setServerName("").build(), snap = snapshot().copy(hostname = "9f8e7d6c5b4a")) shouldBe
                SpecDiff.Match
        }

        test("stop_command is informational and never forces a recreate") {
            diff(s = spec().toBuilder().setStopCommand("^C").build()) shouldBe SpecDiff.Match
        }

        test("multiple mismatches are reported together") {
            diff(snap = snapshot().copy(image = "other:1", memoryMb = 1)) shouldBe
                SpecDiff.Mismatch(listOf(SpecDiffReason.IMAGE, SpecDiffReason.MEMORY))
        }
    })
