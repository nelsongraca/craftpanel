package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

fun globalCraftpanelCleanup() {
    val client = DockerClientFactory.instance().client()
    var removedContainers = 0
    var removedNetworks = 0

    runCatching {
        val containers = client.listContainersCmd()
            .withShowAll(true)
            .withNameFilter(listOf("craftpanel-"))
            .exec()
        for (container in containers) {
            runCatching {
                client.removeContainerCmd(container.id)
                    .withForce(true)
                    .exec()
                removedContainers++
            }
        }
    }

    runCatching {
        val networks = client.listNetworksCmd()
            .exec()
            .filter { it.name?.startsWith("craftpanel-") == true }
        for (net in networks) {
            runCatching {
                client.removeNetworkCmd(net.id)
                    .exec()
                removedNetworks++
            }
        }
    }

    if (removedContainers > 0 || removedNetworks > 0) {
        System.err.println("[global-cleanup] Removed $removedContainers craftpanel container(s) and $removedNetworks network(s)")
    }
}

private class PgContainer : GenericContainer<PgContainer>("postgres:16")
private class MasterContainer : GenericContainer<MasterContainer>("ghcr.io/nelsongraca/craftpanel/master:latest")
private class AgentContainer : GenericContainer<AgentContainer>("ghcr.io/nelsongraca/craftpanel/agent:latest")

private const val DB_NAME = "craftpanel"
private const val DB_USER = "craftpanel"
private const val DB_PASSWORD = "craftpanel-test"

const val ADMIN_EMAIL = "admin@craftpanel.test"
const val ADMIN_PASSWORD = "admin-test-password"

class CraftPanelStack {

    private val networkSuffix: String = buildString {
        repeat(4) {
            append(
                Random.nextInt(16)
                    .toString(16)
            )
        }
    }

    val containerPrefix: String = "craftpanel-$networkSuffix"
    val networkName: String get() = "craftpanel-$networkSuffix"

    private lateinit var postgres: PgContainer
    private lateinit var master: MasterContainer
    private val agents = mutableListOf<AgentContainer>()
    private val agentAliasCounter = AtomicInteger(0)
    private var craftpanelNetworkId: String? = null
    private val testDataDirs = mutableListOf<File>()
    private var coverageMode = false
    private var agentJar: File? = null
    private var coverageDir: File? = null
    private var _gatewayIp: String? = null
    private val agentDataDirs = mutableMapOf<String, File>()

    var nodeId: String = ""
        private set

    private val _nodeIds = mutableListOf<String>()
    val nodeIds: List<String> get() = _nodeIds

    val masterApiUrl: String
        get() = "http://localhost:${master.getMappedPort(8080)}"

    val agentContainerId: String
        get() = agents.first().containerId

    val agentContainerIds: List<String>
        get() = agents.map { it.containerId }

    val dockerClient: DockerClient
        get() = DockerClientFactory.instance()
            .client()

    fun storeNodeId(id: String) {
        nodeId = id
    }

    fun storeNodeIds(ids: List<String>) {
        _nodeIds.clear()
        _nodeIds.addAll(ids)
        nodeId = ids.firstOrNull() ?: ""
    }

    fun start(
        coverageEnabled: Boolean = false,
        agentJar: File? = null,
        coverageDir: File? = null,
        nodeCount: Int = 1,
    ) {
        this.coverageMode = coverageEnabled
        this.agentJar = agentJar
        this.coverageDir = coverageDir
        try {
            startInternal(nodeCount)
        } catch (t: Throwable) {
            runCatching { stop() }.onFailure { stopErr ->
                System.err.println("[start-cleanup] stop() after failed start threw: ${stopErr.message}")
            }
            throw t
        }
    }

    private fun startInternal(nodeCount: Int) {
        cleanupAgentContainers()

        runCatching {
            val existingNetworks = dockerClient.listNetworksCmd()
                .withNameFilter("craftpanel-$networkSuffix")
                .exec()
                .filter { it.name == "craftpanel-$networkSuffix" }
            for (net in existingNetworks) {
                val containers = net.containers?.keys ?: emptySet()
                for (containerId in containers) {
                    runCatching {
                        dockerClient.disconnectFromNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(net.id)
                            .exec()
                    }
                }
                dockerClient.removeNetworkCmd(net.id)
                    .exec()
            }
        }

        craftpanelNetworkId = dockerClient.createNetworkCmd()
            .withName(networkName)
            .exec().id

        val gatewayIp = computeGatewayIp()

        postgres = PgContainer()
            .withNetworkMode(networkName)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withAliases("postgres")
                cmd.withName("$containerPrefix-postgres")
            }
            .withEnv("POSTGRES_DB", DB_NAME)
            .withEnv("POSTGRES_USER", DB_USER)
            .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forSuccessfulCommand("pg_isready -U $DB_USER -d $DB_NAME"))

        postgres.start()

        val jar = agentJar
        val covDir = coverageDir
        master = MasterContainer()
            .withNetworkMode(networkName)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withAliases("master")
                cmd.withName("$containerPrefix-master")
            }
            .withExposedPorts(8080)
            .withEnv("CRAFTPANEL_PROFILE", "dev")
            .withEnv("DATABASE_URL", "jdbc:postgresql://postgres:5432/$DB_NAME")
            .withEnv("DATABASE_USERNAME", DB_USER)
            .withEnv("DATABASE_PASSWORD", DB_PASSWORD)
            .withEnv("NODE_BOOTSTRAP_TOKEN", "test-bootstrap-token")
            .withEnv("JWT_SECRET", "test-jwt-secret-at-least-32-characters-long!")
            .withEnv("CRAFTPANEL_CONTAINER_PREFIX", containerPrefix)
            .withEnv("CRAFTPANEL_IMAGE_OVERRIDE_MINECRAFT", "craftpanel-fake-server")
            .withEnv("CRAFTPANEL_IMAGE_OVERRIDE_PROXY", "craftpanel-fake-proxy")
            .withEnv("CRAFTPANEL_ADMIN_EMAIL", ADMIN_EMAIL)
            .withEnv("CRAFTPANEL_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("RATE_LIMIT_LOGIN", "1000")
            .withEnv("RATE_LIMIT_REFRESH", "1000")
            .apply {
                withLogConsumer { frame -> System.err.println("[master] ${frame.utf8String.trimEnd()}") }
                if (coverageMode && jar != null && covDir != null) {
                    covDir.mkdirs()
                    covDir.setWritable(true, false)
                    covDir.setReadable(true, false)
                    covDir.setExecutable(true, false)
                    val masterSuffix = containerPrefix.removePrefix("craftpanel-")
                    val masterReport = "master-$masterSuffix.ic"
                    val masterArgs = "master-$masterSuffix-kover.args"
                    File(covDir, masterArgs).writeText("report.file=/tmp/coverage/$masterReport\ninclude=io.craftpanel.*")
                    withFileSystemBind(jar.absolutePath, "/opt/kover/agent.jar", BindMode.READ_ONLY)
                    withFileSystemBind(covDir.absolutePath, "/tmp/coverage", BindMode.READ_WRITE)
                    withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opt/kover/agent.jar=file:/tmp/coverage/$masterArgs")
                }
            }
            .waitingFor(Wait.forLogMessage(".*Responding at.*", 1))

        master.start()

        repeat(nodeCount) {
            val index = agentAliasCounter.getAndIncrement()
            val container = createAgentContainer(index, networkName, gatewayIp)
            container.start()
            agents.add(container)
        }
    }

    private fun createAgentContainer(index: Int, networkName: String, gatewayIp: String): AgentContainer {
        val alias = if (index == 0) "agent" else "agent-$index"

        val dataDir = Files.createTempDirectory("craftpanel-test-data-")
            .toFile()
        Files.setPosixFilePermissions(
            dataDir.toPath(),
            EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
            )
        )
        dataDir.deleteOnExit()
        testDataDirs.add(dataDir)
        println("[setup] Agent $index test data directory: ${dataDir.absolutePath}")

        return AgentContainer()
            .withNetworkMode(networkName)
            .withCreateContainerCmdModifier { cmd ->
                cmd.withAliases(alias)
                cmd.withName("$containerPrefix-$alias")
            }
            .withEnv("APP_PROFILE", "dev")
            .withEnv("MASTER_HOST", "master")
            .withEnv("MASTER_GRPC_PORT", "50051")
            .withEnv("NODE_BOOTSTRAP_TOKEN", "test-bootstrap-token")
            .withEnv("DATA_PATH", "/data")
            .withEnv("CRAFTPANEL_CONTAINER_PREFIX", containerPrefix)
            .withEnv("CRAFTPANEL_NETWORK", networkName)
            .withEnv("NODE_PRIVATE_IP", gatewayIp)
            .withEnv("HOST_DATA_PATH", dataDir.absolutePath)
            .withEnv("NODE_HOSTNAME", alias)
            .withEnv("METRICS_POLL_INTERVAL_SECONDS", "5")
            .withFileSystemBind(dataDir.absolutePath, "/data", BindMode.READ_WRITE)
            .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE)
            .apply {
                val jar = agentJar
                val covDir = coverageDir
                withLogConsumer { frame -> System.err.println("[agent-$index] ${frame.utf8String.trimEnd()}") }
                if (coverageMode && jar != null && covDir != null) {
                    val agentSuffix = containerPrefix.removePrefix("craftpanel-")
                    val agentReport = "agent-$agentSuffix-$index.ic"
                    val agentArgs = "agent-$agentSuffix-$index-kover.args"
                    File(covDir, agentArgs).writeText("report.file=/tmp/coverage/$agentReport\ninclude=io.craftpanel.*")
                    withFileSystemBind(jar.absolutePath, "/opt/kover/agent.jar", BindMode.READ_ONLY)
                    withFileSystemBind(covDir.absolutePath, "/tmp/coverage", BindMode.READ_WRITE)
                    withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opt/kover/agent.jar=file:/tmp/coverage/$agentArgs")
                }
            }
            .also { agentDataDirs[it.containerId] = dataDir }
    }

    private fun computeGatewayIp(): String {
        if (_gatewayIp == null) {
            val networkId = dockerClient.listNetworksCmd()
                .withNameFilter(networkName)
                .exec()
                .firstOrNull()?.id
                ?: error("Network '$networkName' not found")
            _gatewayIp = dockerClient.inspectNetworkCmd()
                .withNetworkId(networkId)
                .exec()
                .ipam?.config?.firstOrNull()?.gateway
                ?: error("No gateway IP for network '$networkName'")
        }
        return _gatewayIp!!
    }

    fun addAgent(): String {
        val index = agentAliasCounter.getAndIncrement()
        val gatewayIp = computeGatewayIp()
        val container = createAgentContainer(index, networkName, gatewayIp)
        container.start()
        agents.add(container)
        return container.containerId
    }

    fun removeAgent(containerId: String) {
        val container = agents.find { it.containerId == containerId }
        container?.let { gracefulStop(it) }
        agents.removeAll { it.containerId == containerId }
        agentDataDirs.remove(containerId)?.let { dir ->
            runCatching { dir.deleteRecursively() }
        }
        runCatching {
            dockerClient.removeContainerCmd(containerId)
                .withForce(true)
                .exec()
        }
    }

    private fun gracefulStop(container: GenericContainer<*>) {
        if (coverageMode) {
            runCatching {
                dockerClient.stopContainerCmd(container.containerId)
                    .withTimeout(30)
                    .exec()
            }
        }
        container.stop()
    }

    fun stop() {
        stopAllPrefixedContainers()
        agents.forEach { gracefulStop(it) }
        cleanupAgentContainers()
        if (::master.isInitialized) gracefulStop(master)
        if (::postgres.isInitialized) postgres.stop()
        craftpanelNetworkId?.let { id ->
            runCatching {
                dockerClient.removeNetworkCmd(id)
                    .exec()
            }
        }
        verifyContainersRemoved()
        testDataDirs.forEach { dir ->
            runCatching {
                dir.deleteRecursively()
                println("[cleanup] Removed test data directory: ${dir.absolutePath}")
            }
        }
        agents.clear()
    }

    private fun verifyContainersRemoved(attempts: Int = 5, delayMs: Long = 500) {
        repeat(attempts) { attempt ->
            val remaining = runCatching {
                dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(listOf("$containerPrefix-"))
                    .exec()
            }.getOrElse { return }
            if (remaining.isEmpty()) return
            if (attempt < attempts - 1) {
                Thread.sleep(delayMs)
                remaining.forEach { container ->
                    runCatching {
                        dockerClient.removeContainerCmd(container.id)
                            .withForce(true)
                            .exec()
                    }
                }
            } else {
                System.err.println(
                    "[cleanup] WARNING: ${remaining.size} container(s) still present after $attempts attempts: " +
                        remaining.joinToString { it.names.firstOrNull() ?: it.id }
                )
            }
        }
    }

    private fun stopAllPrefixedContainers() {
        runCatching {
            val containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withNameFilter(listOf("$containerPrefix-"))
                .exec()
            for (container in containers) {
                runCatching {
                    dockerClient.stopContainerCmd(container.id)
                        .exec()
                    println("[cleanup] Stopped container ${container.names.firstOrNull()}")
                }
            }
        }
    }

    private fun cleanupAgentContainers() {
        runCatching {
            val client = DockerClientFactory.instance()
                .client()
            val containers = client.listContainersCmd()
                .withShowAll(true)
                .withNameFilter(listOf("$containerPrefix-"))
                .exec()
            for (container in containers) {
                runCatching {
                    client.removeContainerCmd(container.id)
                        .withForce(true)
                        .exec()
                    println("[cleanup] Removed container ${container.names.firstOrNull()}")
                }
            }
        }
    }
}

val SharedStack = CraftPanelStack()
