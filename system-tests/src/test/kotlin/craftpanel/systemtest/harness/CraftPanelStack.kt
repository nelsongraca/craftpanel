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
import kotlin.random.Random

// Self-referential subclasses — standard Kotlin idiom for Testcontainers to avoid
// the Nothing/wildcard type-parameter issues with the fluent builder API.
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

    private lateinit var postgres: PgContainer
    private lateinit var master: MasterContainer
    private val agents = mutableListOf<AgentContainer>()
    private var craftpanelNetworkId: String? = null
    private val testDataDirs = mutableListOf<File>()
    private var coverageMode = false

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
        coverageMode = coverageEnabled
        // All containers share the craftpanel network so the agent self-check passes at startup
        // and game server containers are reachable from the agent without post-start hacks.

        // Remove any orphaned agent-managed containers from previous runs that might hold
        // stale port bindings.
        cleanupAgentContainers()

        // Remove any leftover network from a previous run to avoid ConflictException.
        // First disconnect all containers from the network, then remove it.
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
            .withName("craftpanel-$networkSuffix")
            .exec().id

        postgres = PgContainer()
            .withNetworkMode("craftpanel-$networkSuffix")
            .withCreateContainerCmdModifier { cmd -> cmd.withAliases("postgres") }
            .withEnv("POSTGRES_DB", DB_NAME)
            .withEnv("POSTGRES_USER", DB_USER)
            .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forSuccessfulCommand("pg_isready -U $DB_USER -d $DB_NAME"))

        postgres.start()

        master = MasterContainer()
            .withNetworkMode("craftpanel-$networkSuffix")
            .withCreateContainerCmdModifier { cmd -> cmd.withAliases("master") }
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
                if (coverageEnabled && agentJar != null && coverageDir != null) {
                    coverageDir.mkdirs()
                    coverageDir.setWritable(true, false)
                    coverageDir.setReadable(true, false)
                    coverageDir.setExecutable(true, false)
                    val masterSuffix = containerPrefix.removePrefix("craftpanel-")
                    val masterReport = "master-$masterSuffix.ic"
                    val masterArgs = "master-$masterSuffix-kover.args"
                    File(coverageDir, masterArgs).writeText("report.file=/tmp/coverage/$masterReport\ninclude=io.craftpanel.*")
                    withFileSystemBind(agentJar.absolutePath, "/opt/kover/agent.jar", BindMode.READ_ONLY)
                    withFileSystemBind(coverageDir.absolutePath, "/tmp/coverage", BindMode.READ_WRITE)
                    withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opt/kover/agent.jar=file:/tmp/coverage/$masterArgs")
                }
            }
            .waitingFor(Wait.forLogMessage(".*Responding at.*", 1))

        master.start()

        for (i in 0 until nodeCount) {
            val alias = if (i == 0) "agent" else "agent-$i"
            val hostname = alias

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
            println("[setup] Agent $i test data directory: ${dataDir.absolutePath}")

            val agentContainer = AgentContainer()
                .withNetworkMode("craftpanel-$networkSuffix")
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
                .withEnv("CRAFTPANEL_NETWORK", "craftpanel-$networkSuffix")
                .withEnv("HOST_DATA_PATH", dataDir.absolutePath)
                .withEnv("NODE_HOSTNAME", hostname)
                .withEnv("METRICS_POLL_INTERVAL_SECONDS", "5")
                .withFileSystemBind(dataDir.absolutePath, "/data", BindMode.READ_WRITE)
                .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE)
                .apply {
                    withLogConsumer { frame -> System.err.println("[agent-$i] ${frame.utf8String.trimEnd()}") }
                    if (coverageEnabled && agentJar != null && coverageDir != null) {
                        val agentSuffix = containerPrefix.removePrefix("craftpanel-")
                        val agentReport = "agent-$agentSuffix-$i.ic"
                        val agentArgs = "agent-$agentSuffix-$i-kover.args"
                        File(coverageDir, agentArgs).writeText("report.file=/tmp/coverage/$agentReport\ninclude=io.craftpanel.*")
                        withFileSystemBind(agentJar.absolutePath, "/opt/kover/agent.jar", BindMode.READ_ONLY)
                        withFileSystemBind(coverageDir.absolutePath, "/tmp/coverage", BindMode.READ_WRITE)
                        withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opt/kover/agent.jar=file:/tmp/coverage/$agentArgs")
                    }
                }

            agentContainer.start()
            agents.add(agentContainer)
        }
    }

    private fun gracefulStop(container: GenericContainer<*>) {
        if (coverageMode) {
            // Testcontainers 2.x ResourceReaper sends SIGKILL directly, so JVM shutdown hooks
            // (including Kover's coverage flush) never run. Send SIGTERM first and wait up to
            // 30 seconds for the JVM to flush coverage data before Testcontainers cleans up.
            runCatching {
                dockerClient.stopContainerCmd(container.containerId)
                    .withTimeout(30)
                    .exec()
            }
        }
        container.stop()
    }

    fun stop() {
        agents.forEach { gracefulStop(it) }
        stopAllPrefixedContainers()
        cleanupAgentContainers()
        if (::master.isInitialized) gracefulStop(master)
        if (::postgres.isInitialized) postgres.stop()
        craftpanelNetworkId?.let { id ->
            runCatching {
                dockerClient.removeNetworkCmd(id)
                    .exec()
            }
        }
        testDataDirs.forEach { dir ->
            runCatching {
                dir.deleteRecursively()
                println("[cleanup] Removed test data directory: ${dir.absolutePath}")
            }
        }
        agents.clear()
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
