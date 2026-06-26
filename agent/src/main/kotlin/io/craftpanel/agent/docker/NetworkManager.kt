package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.exception.ConflictException
import com.github.dockerjava.api.model.Network
import org.slf4j.LoggerFactory

class NetworkManager(
    private val docker: DockerClient,
    private val mcRouterContainerName: String,
) {

    private val log = LoggerFactory.getLogger(NetworkManager::class.java)

    /** Creates the bridge network if it does not exist yet. Call before createContainer. */
    fun ensureNetwork(networkName: String) {
        ensureNetworkExists(networkName)
    }

    /** Attaches mc-router to the network after container creation (container already joined via withNetworkMode). */
    fun attachToNetwork(networkName: String) {
        attachMcRouter(networkName)
    }

    /** Detach mc-router from network and delete it if no other craftpanel containers remain. */
    fun maybeDetachAndDelete(networkName: String, removingContainerId: String) {
        val net = findNetwork(networkName) ?: return
        val remainingContainers = net.containers.orEmpty()
            .keys
            .filter { id -> id != removingContainerId && id != getMcRouterId() }
        if (remainingContainers.isEmpty()) {
            detachMcRouter(networkName)
            runCatching {
                docker.removeNetworkCmd(net.id)
                    .exec()
                log.info("Deleted network $networkName (last server removed)")
            }.onFailure { log.warn("Failed to delete network $networkName: ${it.message}") }
        }
    }

    private fun ensureNetworkExists(networkName: String) {
        if (findNetwork(networkName) != null) return
        runCatching {
            docker.createNetworkCmd()
                .withName(networkName)
                .withDriver("bridge")
                .withLabels(mapOf("craftpanel.managed" to "true"))
                .exec()
            log.info("Created bridge network $networkName")
        }.onFailure { e ->
            if (e is ConflictException) {
                log.info("Network $networkName already exists (created by another agent) — reusing")
            }
            else {
                log.warn("Failed to create network $networkName: ${e.message}")
            }
        }
    }

    private fun attachMcRouter(networkName: String) {
        val routerId = getMcRouterId() ?: run {
            log.warn("mc-router not found — cannot attach to $networkName")
            return
        }
        val net = findNetwork(networkName) ?: return
        if (net.containers.orEmpty()
                .containsKey(routerId)
        ) return
        runCatching {
            docker.connectToNetworkCmd()
                .withNetworkId(networkName)
                .withContainerId(routerId)
                .exec()
            log.info("Attached mc-router to $networkName")
        }.onFailure { log.warn("Failed to attach mc-router to $networkName: ${it.message}") }
    }

    private fun detachMcRouter(networkName: String) {
        val routerId = getMcRouterId() ?: return
        runCatching {
            docker.disconnectFromNetworkCmd()
                .withNetworkId(networkName)
                .withContainerId(routerId)
                .exec()
            log.info("Detached mc-router from $networkName")
        }.onFailure { log.warn("Failed to detach mc-router from $networkName: ${it.message}") }
    }

    private fun findNetwork(networkName: String): Network? =
        runCatching {
            docker.listNetworksCmd()
                .withNameFilter(networkName)
                .exec()
                .firstOrNull { it.name == networkName }
        }.getOrNull()

    private fun getMcRouterId(): String? =
        runCatching {
            docker.inspectContainerCmd(mcRouterContainerName)
                .exec().id
        }.getOrNull()
}
