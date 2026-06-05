package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait

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

object CraftPanelStack {

    private lateinit var network: Network
    private lateinit var postgres: PgContainer
    private lateinit var master: MasterContainer
    private lateinit var agent: AgentContainer

    val masterApiUrl: String
        get() = "http://localhost:${master.getMappedPort(8080)}"

    val dockerClient: DockerClient
        get() = DockerClientFactory.instance()
            .client()

    fun start() {
        network = Network.newNetwork()

        postgres = PgContainer()
            .withNetwork(network)
            .withNetworkAliases("postgres")

            .withEnv("POSTGRES_DB", DB_NAME)
            .withEnv("POSTGRES_USER", DB_USER)
            .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forSuccessfulCommand("pg_isready -U $DB_USER -d $DB_NAME"))

        postgres.start()

        master = MasterContainer()
            .withNetwork(network)
            .withNetworkAliases("master")
            .withExposedPorts(8080)
            .withEnv("CRAFTPANEL_PROFILE", "dev")
            .withEnv("DATABASE_URL", "jdbc:postgresql://postgres:5432/$DB_NAME")
            .withEnv("DATABASE_USERNAME", DB_USER)
            .withEnv("DATABASE_PASSWORD", DB_PASSWORD)
            .withEnv("NODE_BOOTSTRAP_TOKEN", "test-bootstrap-token")
            .withEnv("JWT_SECRET", "test-jwt-secret-at-least-32-characters-long!")
            .withEnv("CRAFTPANEL_IMAGE_OVERRIDE_MINECRAFT", "craftpanel-fake-server")
            .withEnv("CRAFTPANEL_IMAGE_OVERRIDE_PROXY", "craftpanel-fake-proxy")
            .withEnv("CRAFTPANEL_ADMIN_EMAIL", ADMIN_EMAIL)
            .withEnv("CRAFTPANEL_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withLogConsumer { frame -> System.err.println("[master] ${frame.utf8String.trimEnd()}") }
            .waitingFor(Wait.forLogMessage(".*Responding at.*", 1))

        master.start()

        agent = AgentContainer()
            .withNetwork(network)
            .withNetworkAliases("agent")
            .withEnv("APP_PROFILE", "dev")
            .withEnv("MASTER_HOST", "master")
            .withEnv("MASTER_GRPC_PORT", "50051")
            .withEnv("NODE_BOOTSTRAP_TOKEN", "test-bootstrap-token")
            .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE)
            .withLogConsumer { frame -> System.err.println("[agent] ${frame.utf8String.trimEnd()}") }
            .waitingFor(Wait.forLogMessage(".*Sent NodeStateSnapshot.*", 1))

        agent.start()
    }

    fun stop() {
        if (::agent.isInitialized) agent.stop()
        cleanupAgentContainers()
        if (::master.isInitialized) master.stop()
        if (::postgres.isInitialized) postgres.stop()
        if (::network.isInitialized) network.close()
    }

    private fun cleanupAgentContainers() {
        runCatching {
            val client = DockerClientFactory.instance()
                .client()
            val containers = client.listContainersCmd()
                .withShowAll(true)
                .withNameFilter(listOf("craftpanel-"))
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
