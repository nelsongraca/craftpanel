package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.craftpanel.agent.docker.DockerNetworkOps")

/**
 * Idempotently attaches [containerId] to [networkName]. A no-op when the container is already
 * connected, and a benign race (another co-located agent connected it first) is logged rather
 * than thrown — the desired end state already holds.
 */
internal fun DockerClient.connectIfAbsent(networkName: String, containerId: String) {
    val alreadyConnected = runCatching {
        inspectContainerCmd(containerId)
            .exec()
            .networkSettings
            ?.networks
            ?.containsKey(networkName) == true
    }.getOrDefault(false)
    if (alreadyConnected) return
    runCatching {
        connectToNetworkCmd()
            .withNetworkId(networkName)
            .withContainerId(containerId)
            .exec()
    }.onFailure {
        if (it.message?.contains("already exists in network") == true) {
            log.debug("Container {} already connected to {}", containerId, networkName)
        } else {
            log.warn("Could not connect container {} to {}: {}", containerId, networkName, it.message)
        }
    }
}
