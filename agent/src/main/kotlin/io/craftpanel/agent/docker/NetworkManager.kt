package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.exception.ConflictException
import com.github.dockerjava.api.model.Network
import io.craftpanel.common.ContainerNames
import io.craftpanel.common.DockerLabels
import org.slf4j.LoggerFactory

class NetworkManager(
    private val docker: DockerClient,
    private val mcRouterContainerName: String,
    private val mcRouterEnabled: Boolean = true,
    containerPrefix: String = ContainerNames.DEFAULT_PREFIX
) {

    private val log = LoggerFactory.getLogger(NetworkManager::class.java)
    private val names = ContainerNames(containerPrefix)

    /** Creates the bridge network if it does not exist yet. Call before createContainer. */
    fun ensureNetwork(networkName: String) {
        if (findNetwork(networkName) != null) return
        runCatching {
            docker.createNetworkCmd()
                .withName(networkName)
                .withDriver("bridge")
                .withLabels(mapOf(DockerLabels.MANAGED to DockerLabels.MANAGED_VALUE))
                .exec()
            log.info("Created bridge network $networkName")
        }.onFailure { e ->
            if (e is ConflictException) {
                log.info("Network $networkName already exists (created by another agent) — reusing")
            } else {
                log.warn("Failed to create network $networkName: ${e.message}")
            }
        }
    }

    /** Attaches mc-router to the network after container creation (container already joined via withNetworkMode). */
    fun attachToNetwork(networkName: String) {
        if (!mcRouterEnabled) return
        val routerId = getMcRouterId() ?: run {
            log.warn("mc-router not found — cannot attach to $networkName")
            return
        }
        findNetwork(networkName) ?: return
        docker.connectIfAbsent(networkName, routerId)
    }

    /**
     * Attaches mc-router to every managed server network on this host (`<prefix>-net-*` /
     * `<prefix>-server-*`). Called on agent start and whenever the router is (re)created: a fresh
     * router has no per-server attachments, and each container's `mc-router.network` label points at
     * its own server network.
     */
    fun reconcileRouterAttachments() {
        if (!mcRouterEnabled) return
        val routerId = getMcRouterId() ?: run {
            log.warn("mc-router not found — cannot reconcile server network attachments")
            return
        }
        val attached = runCatching {
            docker.inspectContainerCmd(mcRouterContainerName)
                .exec().networkSettings?.networks?.keys.orEmpty()
        }.getOrDefault(emptySet())
        val missing = listManagedNetworks().filter { it !in attached }
        if (missing.isEmpty()) return
        missing.forEach { docker.connectIfAbsent(it, routerId) }
        log.info("Attached mc-router to ${missing.size} server network(s): $missing")
    }

    /**
     * Detach mc-router from the network and delete the network if no other containers remain — except
     * for `overlay` networks, which master owns (created and removed with the Server Network). For an
     * overlay only the router is detached; the network is left in place.
     */
    fun maybeDetachAndDelete(networkName: String, removingContainerId: String) {
        val net = findNetwork(networkName) ?: return
        val remainingContainers = net.containers.orEmpty()
            .keys
            .filter { id -> id != removingContainerId && id != getMcRouterId() }
        if (remainingContainers.isEmpty()) {
            detachMcRouter(networkName)
            if (net.driver == "overlay") {
                log.info("Left overlay network $networkName in place (master-owned) — last local server removed")
                return
            }
            runCatching {
                docker.removeNetworkCmd(net.id)
                    .exec()
                log.info("Deleted network $networkName (last server removed)")
            }.onFailure { log.warn("Failed to delete network $networkName: ${it.message}") }
        }
    }

    private fun detachMcRouter(networkName: String) {
        if (!mcRouterEnabled) return
        val routerId = getMcRouterId() ?: return
        runCatching {
            docker.disconnectFromNetworkCmd()
                .withNetworkId(networkName)
                .withContainerId(routerId)
                .exec()
            log.info("Detached mc-router from $networkName")
        }.onFailure { log.warn("Failed to detach mc-router from $networkName: ${it.message}") }
    }

    private fun findNetwork(networkName: String): Network? = runCatching {
        docker.listNetworksCmd()
            .withNameFilter(networkName)
            .exec()
            .firstOrNull { it.name == networkName }
    }.getOrNull()

    private fun listManagedNetworks(): List<String> = runCatching {
        docker.listNetworksCmd()
            .exec()
            .map { it.name }
            .filter { names.isManagedNetwork(it) }
    }.getOrDefault(emptyList())

    private fun getMcRouterId(): String? = runCatching {
        docker.inspectContainerCmd(mcRouterContainerName)
            .exec().id
    }.getOrNull()
}
