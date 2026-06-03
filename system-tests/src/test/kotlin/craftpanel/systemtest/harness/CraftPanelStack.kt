package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer

// Self-referential subclasses — standard Kotlin idiom for Testcontainers to avoid
// the Nothing/wildcard type-parameter issues with the fluent builder API.
private class PgContainer : PostgreSQLContainer("postgres:16")
private class MasterContainer : GenericContainer<MasterContainer>("craftpanel-master:latest")
private class AgentContainer : GenericContainer<AgentContainer>("craftpanel-agent:latest")

private const val DB_NAME = "craftpanel"
private const val DB_USER = "craftpanel"
private const val DB_PASSWORD = "craftpanel-test"

object CraftPanelStack {

    private lateinit var network: Network
    private lateinit var postgres: PgContainer
    private lateinit var master: MasterContainer
    private lateinit var agent: AgentContainer

    val masterApiUrl: String
        get() = "http://localhost:${master.getMappedPort(8080)}"

    val dockerClient: DockerClient
        get() = DockerClientFactory.instance().client()

    fun start() {
        network = Network.newNetwork()

        postgres = PgContainer().apply {
            withNetwork(network)
            withNetworkAliases("postgres")
            withDatabaseName(DB_NAME)
            withUsername(DB_USER)
            withPassword(DB_PASSWORD)
        }
        postgres.start()

        master = MasterContainer().apply {
            withNetwork(network)
            withNetworkAliases("master")
            withExposedPorts(8080)
            withEnv("CRAFTPANEL_PROFILE", "dev")
            withEnv("DATABASE_URL", "jdbc:postgresql://postgres:5432/$DB_NAME")
            withEnv("DATABASE_USERNAME", DB_USER)
            withEnv("DATABASE_PASSWORD", DB_PASSWORD)
            withEnv("NODE_BOOTSTRAP_TOKEN", "test-bootstrap-token")
            withEnv("JWT_SECRET", "test-jwt-secret-at-least-32-characters-long!")
            withEnv("CRAFTPANEL_IMAGE_OVERRIDE_MINECRAFT", "craftpanel-fake-server:test")
            withEnv("CRAFTPANEL_IMAGE_OVERRIDE_PROXY", "craftpanel-fake-proxy:test")
            waitingFor(Wait.forHttp("/health").forStatusCode(200))
        }
        master.start()

        agent = AgentContainer().apply {
            withNetwork(network)
            withNetworkAliases("agent")
            withEnv("APP_PROFILE", "dev")
            withEnv("MASTER_HOST", "master")
            withEnv("MASTER_GRPC_PORT", "50051")
            withEnv("NODE_BOOTSTRAP_TOKEN", "test-bootstrap-token")
            withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE)
            waitingFor(Wait.forLogMessage(".*Sent NodeStateSnapshot.*", 1))
        }
        agent.start()
    }

    fun stop() {
        if (::agent.isInitialized) agent.stop()
        if (::master.isInitialized) master.stop()
        if (::postgres.isInitialized) postgres.stop()
        if (::network.isInitialized) network.close()
    }
}
