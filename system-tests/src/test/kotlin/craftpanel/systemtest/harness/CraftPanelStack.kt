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
    private lateinit var agent: AgentContainer
    private var craftpanelNetworkId: String? = null
    private var testDataDir: File? = null

    var nodeId: String = ""
        private set

    val masterApiUrl: String
        get() = "http://localhost:${master.getMappedPort(8080)}"

    val agentContainerId: String
        get() = agent.containerId

    val dockerClient: DockerClient
        get() = DockerClientFactory.instance()
            .client()

    fun storeNodeId(id: String) {
        nodeId = id
    }

    fun start(
        coverageEnabled: Boolean = false,
        agentJar: File? = null,
        coverageDir: File? = null,
    ) {
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
            .withLogConsumer { frame -> System.err.println("[master] ${frame.utf8String.trimEnd()}") }
            .waitingFor(Wait.forLogMessage(".*Responding at.*", 1))
            .apply {
                if (coverageEnabled && agentJar != null && coverageDir != null) {
                    val argsFile = File(coverageDir, "master-kover.args").apply {
                        writeText("report.file=/tmp/coverage/master.ic\ninclude=io.craftpanel.*")
                    }
                    withFileSystemBind(agentJar.absolutePath, "/opt/kover/agent.jar", BindMode.READ_ONLY)
                    withFileSystemBind(coverageDir.absolutePath, "/tmp/coverage", BindMode.READ_WRITE)
                    withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opt/kover/agent.jar=file:/tmp/coverage/master-kover.args")
                }
            }

        master.start()

        testDataDir = Files.createTempDirectory("craftpanel-test-data-")
            .toFile()
        Files.setPosixFilePermissions(
            testDataDir!!.toPath(),
            EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
            )
        )
        testDataDir!!.deleteOnExit()
        println("[setup] Test data directory: ${testDataDir!!.absolutePath}")

        agent = AgentContainer()
            .withNetworkMode("craftpanel-$networkSuffix")
            .withCreateContainerCmdModifier { cmd -> cmd.withAliases("agent") }
            .withEnv("APP_PROFILE", "dev")
            .withEnv("MASTER_HOST", "master")
            .withEnv("MASTER_GRPC_PORT", "50051")
            .withEnv("NODE_BOOTSTRAP_TOKEN", "test-bootstrap-token")
            .withEnv("DATA_PATH", "/data")
            .withEnv("CRAFTPANEL_CONTAINER_PREFIX", containerPrefix)
            .withEnv("CRAFTPANEL_NETWORK", "craftpanel-$networkSuffix")
            .withEnv("HOST_DATA_PATH", testDataDir!!.absolutePath)
            .withEnv("METRICS_POLL_INTERVAL_SECONDS", "5")
            .withFileSystemBind(testDataDir!!.absolutePath, "/data", BindMode.READ_WRITE)
            .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE)
            .withLogConsumer { frame -> System.err.println("[agent] ${frame.utf8String.trimEnd()}") }
            .waitingFor(Wait.forLogMessage(".*Sent NodeStateSnapshot.*", 1))
            .apply {
                if (coverageEnabled && agentJar != null && coverageDir != null) {
                    val argsFile = File(coverageDir, "agent-kover.args").apply {
                        writeText("report.file=/tmp/coverage/agent.ic\ninclude=io.craftpanel.*")
                    }
                    withFileSystemBind(agentJar.absolutePath, "/opt/kover/agent.jar", BindMode.READ_ONLY)
                    withFileSystemBind(coverageDir.absolutePath, "/tmp/coverage", BindMode.READ_WRITE)
                    withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opt/kover/agent.jar=file:/tmp/coverage/agent-kover.args")
                }
            }

        agent.start()
    }

    fun stop() {
        if (::agent.isInitialized) agent.stop()
        cleanupAgentContainers()
        if (::master.isInitialized) master.stop()
        if (::postgres.isInitialized) postgres.stop()
        craftpanelNetworkId?.let { id ->
            runCatching {
                dockerClient.removeNetworkCmd(id)
                    .exec()
            }
        }
        testDataDir?.let { dir ->
            runCatching {
                dir.deleteRecursively()
                println("[cleanup] Removed test data directory: ${dir.absolutePath}")
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
