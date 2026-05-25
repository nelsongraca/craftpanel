package io.craftpanel.agent.grpc

import com.craftpanel.agent.v1.*
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.grpc.ManagedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

class ControlStreamHandler(
    private val identity: NodeIdentity,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
) {
    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    suspend fun run(channel: ManagedChannel): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val outbound = MutableSharedFlow<AgentMessage>(extraBufferCapacity = 64)

        val stream = stub.control(outbound)

        // Send NodeStateSnapshot as the first message
        val snapshot = buildStateSnapshot()
        outbound.emit(agentMessage {
            nodeId = identity.nodeId
            nodeState = snapshot
        })
        log.info("Sent NodeStateSnapshot with ${snapshot.containersCount} containers")

        // Periodic metrics loop
        launch {
            while (true) {
                delay(60.seconds)
                val metrics = metricsCollector.collect()
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    nodeMetrics = metrics
                })
            }
        }

        // Process inbound commands from master
        stream.collect { msg ->
            log.debug("Received master command: ${msg.payloadCase}")
            when {
                msg.hasCreateContainer() -> handleCreate(msg.correlationId, msg.createContainer, outbound)
                msg.hasStartContainer() -> handleStart(msg.correlationId, msg.startContainer, outbound)
                msg.hasStopContainer() -> handleStop(msg.correlationId, msg.stopContainer, outbound)
                msg.hasRestartContainer() -> handleRestart(msg.correlationId, msg.restartContainer, outbound)
                msg.hasRemoveContainer() -> handleRemove(msg.correlationId, msg.removeContainer)
                msg.hasPullImage() -> handlePullImage(msg.correlationId, msg.pullImage)
                msg.hasShutdown() -> handleShutdown(msg.shutdown, outbound)
                else -> log.warn("Unhandled master message: ${msg.payloadCase}")
            }
        }
    }

    private fun buildStateSnapshot(): NodeStateSnapshot {
        val containers = containerManager.listContainers()
        return nodeStateSnapshot {
            this.containers.addAll(containers)
            recordedAt = com.google.protobuf.timestamp {
                val now = java.time.Instant.now()
                seconds = now.epochSecond
                nanos = now.nano
            }
        }
    }

    private suspend fun handleCreate(correlationId: String, cmd: CreateContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Creating container ${cmd.containerName} for server ${cmd.serverId}")
        runCatching { containerManager.createContainer(cmd) }
            .onSuccess { dockerContainerId ->
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.STOPPED
                        containerId = dockerContainerId
                    }
                })
            }
            .onFailure { log.error("Failed to create container ${cmd.containerName}", it) }
    }

    private suspend fun handleStart(correlationId: String, cmd: StartContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Starting container ${cmd.containerName}")
        runCatching { containerManager.startContainer(cmd.containerName) }
            .onSuccess {
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.HEALTHY
                    }
                })
            }
            .onFailure {
                log.error("Failed to start container ${cmd.containerName}", it)
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    private suspend fun handleStop(correlationId: String, cmd: StopContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Stopping container ${cmd.containerName}")
        runCatching { containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand) }
            .onSuccess {
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.STOPPED
                    }
                })
            }
            .onFailure {
                log.error("Failed to stop container ${cmd.containerName}", it)
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    private suspend fun handleRestart(correlationId: String, cmd: RestartContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Restarting container ${cmd.containerName}")
        runCatching {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
            containerManager.startContainer(cmd.containerName)
        }
            .onSuccess {
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.HEALTHY
                    }
                })
            }
            .onFailure {
                log.error("Failed to restart container ${cmd.containerName}", it)
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    private suspend fun handleRemove(correlationId: String, cmd: RemoveContainerCommand) {
        log.info("Removing container ${cmd.containerName} (force=${cmd.force})")
        runCatching { containerManager.removeContainer(cmd.containerName, cmd.force) }
            .onFailure { log.error("Failed to remove container ${cmd.containerName}", it) }
    }

    private suspend fun handlePullImage(correlationId: String, cmd: PullImageCommand) {
        log.info("Pulling image ${cmd.image} for server ${cmd.serverId}")
        runCatching { withContext(Dispatchers.IO) { containerManager.pullImage(cmd.image) } }
            .onFailure { log.error("Failed to pull image ${cmd.image}", it) }
    }

    private suspend fun handleShutdown(cmd: ShutdownCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Shutdown requested — stopping all containers gracefully")
        val (graceful, forced) = containerManager.shutdownAll(cmd.timeoutSeconds)
        outbound.emit(agentMessage {
            nodeId = identity.nodeId
            shutdownAcknowledge = shutdownAcknowledgeUpdate {
                gracefulCount = graceful
                forcedCount = forced
            }
        })
    }
}
