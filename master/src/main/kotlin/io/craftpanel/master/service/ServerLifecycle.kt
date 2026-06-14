package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.MasterMessage
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.createContainerCommand
import io.craftpanel.proto.pullImageCommand
import io.craftpanel.proto.removeContainerCommand
import io.craftpanel.proto.startContainerCommand
import io.craftpanel.proto.stopContainerCommand
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ServerLifecycle(
    private val sendToNode: (String, MasterMessage) -> Boolean,
    private val modService: ModService,
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
    private val containerNamePrefix: String = "craftpanel",
    private val clock: Clock = Clock.System,
) {

    fun start(server: ResultRow, pull: Boolean, publicHostname: String? = null) {
        val id = server[Servers.id]
        val nodeId = server[Servers.nodeId].toString()
        val image = deriveImage(server[Servers.serverType], server[Servers.itzgImageTag])
        val allVars = buildAllVars(id, server)
        val resolvedHostname = publicHostname ?: server[Servers.dnsRecordName]

        if (pull || server[Servers.containerId] == null) {
            sendOrThrow(nodeId, masterMessage {
                pullImage = pullImageCommand {
                    serverId = id.toString()
                    this.image = image
                }
            })
            if (server[Servers.containerId] != null) {
                sendOrThrow(nodeId, masterMessage {
                    removeContainer = removeContainerCommand {
                        serverId = id.toString()
                        containerName = "$containerNamePrefix-$id"
                        force = false
                    }
                })
            }
            sendOrThrow(nodeId, buildCreate(id, server, image, allVars, resolvedHostname))
        }

        sendOrThrow(nodeId, masterMessage {
            startContainer = startContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
            }
        })

        writeStatus(id, ServerStatus.STARTING, clearNeedsRecreate = true)
    }

    fun recreateInPlace(server: ResultRow, hostnameOverride: String?) {
        val id = server[Servers.id]
        val nodeId = server[Servers.nodeId].toString()
        val image = deriveImage(server[Servers.serverType], server[Servers.itzgImageTag])
        val allVars = buildAllVars(id, server)
        val publicHostname = hostnameOverride ?: server[Servers.dnsRecordName]

        sendOrThrow(nodeId, masterMessage {
            pullImage = pullImageCommand {
                serverId = id.toString()
                this.image = image
            }
        })
        sendOrThrow(nodeId, masterMessage {
            stopContainer = stopContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                timeoutSeconds = 30
                stopCommand = server[Servers.stopCommand]
            }
        })
        sendOrThrow(nodeId, masterMessage {
            removeContainer = removeContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                force = false
            }
        })
        sendOrThrow(nodeId, buildCreate(id, server, image, allVars, publicHostname))
        sendOrThrow(nodeId, masterMessage {
            startContainer = startContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
            }
        })

        writeStatus(id, ServerStatus.STOPPING, clearNeedsRecreate = true)
    }

    /**
     * Relocation primitives. Migration orchestrates the ordered sequence itself
     * (stop → final rsync → remove → create → start) so it can interleave the
     * post-stop rsync pass and await each cross-node completion signal.
     */

    fun sendStop(server: ResultRow, fromNode: String) {
        val id = server[Servers.id]
        sendOrThrow(fromNode, masterMessage {
            stopContainer = stopContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                timeoutSeconds = 30
                stopCommand = server[Servers.stopCommand]
            }
        })
    }

    fun sendStart(server: ResultRow, node: String) {
        val id = server[Servers.id]
        sendOrThrow(node, masterMessage {
            startContainer = startContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
            }
        })
    }

    fun sendRemove(server: ResultRow, fromNode: String, force: Boolean = false) {
        val id = server[Servers.id]
        sendOrThrow(fromNode, masterMessage {
            removeContainer = removeContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                this.force = force
            }
        })
    }

    fun sendCreate(server: ResultRow, toNode: String) {
        val id = server[Servers.id]
        val image = deriveImage(server[Servers.serverType], server[Servers.itzgImageTag])
        val allVars = buildAllVars(id, server)
        val publicHostname = server[Servers.dnsRecordName]
        sendOrThrow(toNode, buildCreate(id, server, image, allVars, publicHostname))
    }

    fun buildAllVars(id: Uuid, server: ResultRow): Map<String, String> {
        val serverType = server[Servers.serverType]
        val modrinthProjects = modService.buildModrinthEnvVar(id)
        val dbEnvVars = transaction {
            ServerEnvVars.selectAll()
                .where { ServerEnvVars.serverId eq id }
                .associate { it[ServerEnvVars.key] to it[ServerEnvVars.value] }
        }
        val systemVars = buildMap {
            put("EULA", "TRUE")
            put("TYPE", serverType)
            put("VERSION", server[Servers.mcVersion])
            put("MEMORY", "${server[Servers.memoryMb]}M")
            put("ENABLE_QUERY", "TRUE")
            if (modrinthProjects.isNotEmpty()) put("MODRINTH_PROJECTS", modrinthProjects)
        }
        return systemVars + dbEnvVars
    }

    fun buildCreate(
        id: Uuid,
        server: ResultRow,
        image: String,
        allVars: Map<String, String>,
        publicHostname: String?,
    ): MasterMessage = masterMessage {
        createContainer = createContainerCommand {
            serverId = id.toString()
            containerName = "$containerNamePrefix-$id"
            this.image = image
            ramMb = server[Servers.memoryMb]
            cpuShares = server[Servers.cpuShares]
            hostPort = server[Servers.hostPort]
            envVars.putAll(allVars)
            dockerNetwork = server[Servers.networkId]?.let { "$containerNamePrefix-net-$it" } ?: ""
            restartPolicy = "unless-stopped"
            stopCommand = server[Servers.stopCommand]
            mcRouterHostname = publicHostname ?: ""
        }
    }

    private fun deriveImage(serverType: String, tag: String) = images.deriveImage(serverType, tag)

    private fun sendOrThrow(nodeId: String, msg: MasterMessage) {
        if (!sendToNode(nodeId, msg)) throw BadGatewayException("Agent not connected")
    }

    private fun writeStatus(id: Uuid, status: ServerStatus, clearNeedsRecreate: Boolean = false) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.status] = status.toDb()
                if (clearNeedsRecreate) it[Servers.needsRecreate] = false
                it[Servers.updatedAt] = clock.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
    }
}
