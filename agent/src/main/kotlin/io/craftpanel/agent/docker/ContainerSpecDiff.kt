package io.craftpanel.agent.docker

import io.craftpanel.common.ServerPaths
import io.craftpanel.proto.StartContainerCommand

/** A field whose live container configuration does not match the desired spec. */
enum class SpecDiffReason {
    IMAGE,
    USER,
    MEMORY,
    CPU,
    ENV,
    BIND,
    PORTS,
    HOSTNAME_LABEL,
    NETWORK_MODE
}

/** Outcome of comparing a desired spec against the live container's inspected configuration. */
sealed interface SpecDiff {
    /** The live container already satisfies the spec — starting it needs no recreate. */
    data object Match : SpecDiff

    /** The live container differs; [reasons] names every field that mismatched, for logging. */
    data class Mismatch(val reasons: List<SpecDiffReason>) : SpecDiff
}

/**
 * The one module that answers "does the live container satisfy the desired spec?". Pure — no Docker,
 * no config beyond the host data root — so it is directly testable with constructed
 * [ContainerSnapshot]s.
 *
 * Compares every field the agent sets and can read back; env is a subset check (Docker and the image
 * add extra vars). `stop_command` is deliberately **not** compared: it is an agent-side action read
 * from the spec at stop time, so changing it must not force a recreate. A spec field that is empty
 * means "not managed", so the matching snapshot field is not checked.
 */
object ContainerSpecDiff {

    fun diff(spec: StartContainerCommand, snapshot: ContainerSnapshot, hostDataBasePath: String): SpecDiff {
        val reasons = buildList {
            if (snapshot.image != spec.image) add(SpecDiffReason.IMAGE)
            if (snapshot.user != spec.containerUser) add(SpecDiffReason.USER)
            if (snapshot.memoryMb != spec.memoryMb) add(SpecDiffReason.MEMORY)
            if (snapshot.cpuShares != spec.cpuShares) add(SpecDiffReason.CPU)
            for ((key, value) in spec.envVarsMap) {
                if (snapshot.env[key] != value) {
                    add(SpecDiffReason.ENV)
                    break
                }
            }

            val expectedMount = BindSnapshot(
                hostPath = ServerPaths.dataDir(hostDataBasePath, spec.serverId, spec.dataDirName),
                containerPath = spec.dataContainerPath.ifEmpty { "/data" },
                readOnly = false
            )
            if (expectedMount !in snapshot.binds) add(SpecDiffReason.BIND)

            val expectedPorts = buildList {
                add(PortBindingSnapshot(spec.internalListenPort, spec.containerProtocol.ifEmpty { "TCP" }.lowercase(), spec.hostPort))
                spec.extraPortsList.forEach {
                    add(PortBindingSnapshot(it.containerPort, it.protocol.ifEmpty { "TCP" }.lowercase(), it.hostPort))
                }
            }.filter { it.hostPort > 0 }.sortedWith(compareBy({ it.containerPort }, { it.protocol }))
            val actualPorts = snapshot.portBindings.filter { it.hostPort > 0 }
                .sortedWith(compareBy({ it.containerPort }, { it.protocol }))
            if (expectedPorts != actualPorts) add(SpecDiffReason.PORTS)

            if (spec.publicHostname.isNotEmpty() && snapshot.labels["mc-router.host"] != spec.publicHostname) {
                add(SpecDiffReason.HOSTNAME_LABEL)
            }
            if (spec.dockerNetwork.isNotEmpty() && snapshot.networkMode != spec.dockerNetwork) {
                add(SpecDiffReason.NETWORK_MODE)
            }
        }
        return if (reasons.isEmpty()) SpecDiff.Match else SpecDiff.Mismatch(reasons)
    }
}
