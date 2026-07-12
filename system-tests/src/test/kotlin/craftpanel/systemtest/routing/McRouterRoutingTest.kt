package craftpanel.systemtest.routing

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.SharedStack
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exercises the mc-router data path end-to-end: a Minecraft Java handshake carrying a
 * routing hostname is dialled at the per-node mc-router host port (25565) and must land
 * on the correct fake-server backend.
 *
 * This test is RED on the pre-fix agent code:
 *   - the agent labelled containers `mc-router.hostname` (mc-router reads `mc-router.host`), and
 *   - mc-router was started without `IN_DOCKER=true`, so it never read container labels at all.
 * Either defect means the handshake never reaches a backend → the positive assertions fail.
 *
 * Backends are disambiguated by a distinct MOTD per server (fake-server echoes `MOTD`
 * into the status JSON `description.text`).
 */
@Tags("Misc")
class McRouterRoutingTest : BaseSystemTest() {

    // A network with a domain suffix so master can resolve a public hostname for the
    // exposed server in `none` DNS mode (no DNS provider configured). The matching Docker
    // network must exist before the server starts — master sets
    // dockerNetwork=<prefix>-net-<networkId> and nothing else creates it in tests.
    private val domainSuffix = "mcrouter.test"
    private val subdomainA = "alpha-${System.currentTimeMillis()}"
    private val subdomainB = "bravo-${System.currentTimeMillis()}"
    private val hostnameA = "$subdomainA.$domainSuffix"
    private val hostnameB = "$subdomainB.$domainSuffix"
    private val motdA = "ROUTED-TO-ALPHA"
    private val motdB = "ROUTED-TO-BRAVO"

    // Custom hostname test uses a separate server (serverIdC) with both a managed subdomain
    // and a custom hostname so both can be tested simultaneously.
    private val subdomainC = "charlie-${System.currentTimeMillis()}"
    private val hostnameC = "$subdomainC.$domainSuffix"
    private val customHostnameC = "custom-c-${System.currentTimeMillis()}.example.test"
    private val motdC = "ROUTED-TO-CHARLIE"

    private lateinit var networkId: String
    private lateinit var dockerNetworkName: String
    private lateinit var serverIdA: String
    private lateinit var serverIdB: String
    private lateinit var serverIdC: String

    init {
        beforeSpec {
            authHelper.login()
            nodeHelper.pollUntilActive(nodeId)

            val network = api.createNetwork(
                CreateNetworkRequest(
                    name = "mcrouter-net-${System.currentTimeMillis()}",
                    domainSuffix = domainSuffix
                )
            )
            networkId = network.id
            dockerNetworkName = "${SharedStack.containerPrefix}-net-$networkId"
            createDockerNetwork(dockerNetworkName)

            serverIdA = startExposedServer(subdomainA, motdA)
            serverIdB = startExposedServer(subdomainB, motdB)
            serverIdC = startExposedServerWithCustomHostname(subdomainC, customHostnameC, motdC)
        }

        afterSpec {
            runCatching { api.stopServer(serverIdA) }
            runCatching { api.stopServer(serverIdB) }
            runCatching { api.stopServer(serverIdC) }
            runCatching { helper.awaitStoppedOrGone(serverIdA) }
            runCatching { helper.awaitStoppedOrGone(serverIdB) }
            runCatching { helper.awaitStoppedOrGone(serverIdC) }
            runCatching { api.deleteServer(serverIdA) }
            runCatching { api.deleteServer(serverIdB) }
            runCatching { api.deleteServer(serverIdC) }
            runCatching { api.deleteNetwork(networkId) }
            removeDockerNetwork(dockerNetworkName)
        }

        context("mc-router label-driven routing") {

            should("routes a known hostname to its backend") {
                awaitRoutedMotd(hostnameA) shouldContain motdA
            }

            should("routes a second hostname to its own distinct backend") {
                awaitRoutedMotd(hostnameB) shouldContain motdB
            }

            should("does not route an unknown hostname to any backend") {
                // mc-router has no backend for this hostname → it closes the connection
                // without a status response. A successful status read here would mean the
                // router fell through to some backend, which must not happen.
                val routed = runCatching {
                    pingThroughRouter("unknown-${System.currentTimeMillis()}.$domainSuffix")
                }.getOrNull()
                routed shouldBe null
            }
        }

        context("custom domain routing") {

            should("routes the managed subdomain hostname to a server with a custom hostname") {
                awaitRoutedMotd(hostnameC) shouldContain motdC
            }

            should("routes the custom hostname to the same server") {
                awaitRoutedMotd(customHostnameC) shouldContain motdC
            }

            should("canonical_hostname reflects the custom hostname in the API response") {
                val server = api.getServer(serverIdC)
                server.customHostname shouldBe customHostnameC
                server.canonicalHostname shouldBe customHostnameC
            }

            should("clearing the custom hostname drops only that route") {
                // Stop, clear custom hostname, restart
                api.stopServer(serverIdC)
                helper.awaitStoppedOrGone(serverIdC)
                api.updateServerExposure(
                    serverIdC,
                    PatchExposureRequest(
                        exposedExternally = true,
                        publicSubdomain = subdomainC,
                        customHostname = "" // empty string = clear
                    )
                )
                api.startServer(serverIdC)
                helper.awaitStatus(serverIdC, ServerStatus.HEALTHY)

                // Managed subdomain still routes
                awaitRoutedMotd(hostnameC) shouldContain motdC

                // Custom hostname no longer routes — mc-router drops the route when the container
                // is recreated without the customHostnameC label.
                val deadline = System.currentTimeMillis() + 30_000
                var routed: String? = "pending"
                while (System.currentTimeMillis() < deadline) {
                    routed = runCatching { pingThroughRouter(customHostnameC) }.getOrNull()
                    if (routed == null) break
                    delay(500.milliseconds)
                }
                routed shouldBe null

                // canonical_hostname falls back to managed
                val server = api.getServer(serverIdC)
                server.customHostname shouldBe null
                server.canonicalHostname shouldBe hostnameC
            }
        }
    }

    /** Creates a server on the test network, sets its MOTD, marks it externally exposed, starts it, waits HEALTHY. */
    private suspend fun startExposedServer(subdomain: String, motd: String): String {
        val serverId = helper.createTestServer(nodeId, networkId = networkId)
        api.replaceEnvVars(
            serverId,
            PutEnvVarsRequest(envVars = listOf(EnvVarItem(key = "MOTD", value = motd)))
        )
        api.updateServerExposure(
            serverId,
            PatchExposureRequest(exposedExternally = true, publicSubdomain = subdomain)
        )
        api.startServer(serverId)
        helper.awaitStatus(serverId, ServerStatus.HEALTHY)
        return serverId
    }

    /**
     * Creates a server with both a managed subdomain and a custom hostname.
     * Both hostnames should route to the same backend via mc-router.
     */
    private suspend fun startExposedServerWithCustomHostname(subdomain: String, customHostname: String, motd: String): String {
        val serverId = helper.createTestServer(nodeId, networkId = networkId)
        api.replaceEnvVars(
            serverId,
            PutEnvVarsRequest(envVars = listOf(EnvVarItem(key = "MOTD", value = motd)))
        )
        api.updateServerExposure(
            serverId,
            PatchExposureRequest(
                exposedExternally = true,
                publicSubdomain = subdomain,
                customHostname = customHostname
            )
        )
        api.startServer(serverId)
        helper.awaitStatus(serverId, ServerStatus.HEALTHY)
        return serverId
    }

    /** Polls mc-router until the hostname routes to a backend that returns a status MOTD, or times out. */
    private suspend fun awaitRoutedMotd(hostname: String, timeoutMs: Long = 60_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var interval = 250L
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val attempt = runCatching { pingThroughRouter(hostname) }
            attempt.getOrNull()
                ?.let { return it }
            lastError = attempt.exceptionOrNull()
            delay(interval.milliseconds)
            interval = (interval * 1.5).toLong()
                .coerceAtMost(2000)
        }
        System.err.println("[mcrouter-diag] last ping error for '$hostname': ${lastError?.javaClass?.simpleName}: ${lastError?.message}")
        dumpRouterDiagnostics(hostname)
        error("Hostname '$hostname' never routed through mc-router within ${timeoutMs}ms")
    }

    /** On a routing timeout, dump the mc-router container's logs + attached networks so the
     *  discovery decision (or the reason it can't dial the backend) is visible in the report. */
    private suspend fun dumpRouterDiagnostics(hostname: String) = withContext(Dispatchers.IO) {
        val docker = SharedStack.dockerClient
        val routerName = SharedStack.mcRouterContainerName
        runCatching {
            val router = docker.inspectContainerCmd(routerName)
                .exec()
            val routerNets = router.networkSettings?.networks?.keys ?: emptySet()
            System.err.println("[mcrouter-diag] router '$routerName' networks: $routerNets")
            System.err.println("[mcrouter-diag] router state: running=${router.state?.running} status=${router.state?.status}")
            System.err.println("[mcrouter-diag] router host port bindings: ${router.networkSettings?.ports?.bindings}")
            System.err.println(
                "[mcrouter-diag] router env IN_DOCKER: " +
                    (router.config?.env?.firstOrNull { it.startsWith("IN_DOCKER") } ?: "<unset>")
            )

            // Backends carrying the routing label and the networks they live on.
            docker.listContainersCmd()
                .withLabelFilter(listOf("mc-router.host"))
                .exec()
                .forEach { c ->
                    val nets = runCatching {
                        docker.inspectContainerCmd(c.id)
                            .exec().networkSettings?.networks?.keys
                    }.getOrNull() ?: emptySet()
                    System.err.println(
                        "[mcrouter-diag] backend ${c.names?.joinToString()} " +
                            "host=${c.labels?.get("mc-router.host")} network=${c.labels?.get("mc-router.network")} attachedTo=$nets"
                    )
                    System.err.println("[mcrouter-diag] backend ${c.names?.joinToString()} logs:\n${tailLogs(c.id)}")
                }

            System.err.println("[mcrouter-diag] last router logs for '$hostname':\n${tailLogs(routerName)}")
        }.onFailure { System.err.println("[mcrouter-diag] failed: ${it.message}") }
    }

    private fun tailLogs(containerIdOrName: String): String {
        val logs = StringBuilder()
        SharedStack.dockerClient.logContainerCmd(containerIdOrName)
            .withStdOut(true)
            .withStdErr(true)
            .withTail(60)
            .exec(object : com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
                override fun onNext(frame: com.github.dockerjava.api.model.Frame) {
                    logs.append(String(frame.payload))
                }
            })
            .awaitCompletion()
        return logs.toString()
    }

    /**
     * Opens a TCP connection to mc-router and performs a Minecraft Java status ping with the
     * given [serverAddress] in the handshake. Returns the status JSON, or throws if no backend
     * answered (connection refused / closed before a response).
     */
    private suspend fun pingThroughRouter(serverAddress: String): String = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(SharedStack.mcRouterHost, SharedStack.mcRouterPort), 5_000)
            socket.soTimeout = 5_000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Handshake (packet id 0x00): protocolVersion, serverAddress, serverPort, nextState=1 (status)
            val handshake = buildPacket {
                writeVarInt(it, 0x00)
                writeVarInt(it, 769) // protocol 1.21.4
                writeString(it, serverAddress)
                it.write((SharedStack.mcRouterPort ushr 8) and 0xFF)
                it.write(SharedStack.mcRouterPort and 0xFF)
                writeVarInt(it, 1) // next state: status
            }
            out.write(handshake)

            // Status request (packet id 0x00, empty body)
            out.write(buildPacket { writeVarInt(it, 0x00) })
            out.flush()

            // Status response: VarInt(len) VarInt(0x00) VarInt(jsonLen) json
            readVarInt(input) // packet length
            val packetId = readVarInt(input)
            require(packetId == 0x00) { "Unexpected status packet id $packetId" }
            val jsonLen = readVarInt(input)
            val jsonBytes = ByteArray(jsonLen)
            input.readFully(jsonBytes)
            String(jsonBytes, Charsets.UTF_8)
        }
    }

    // --- Docker network helpers ---

    private suspend fun createDockerNetwork(name: String) = withContext(Dispatchers.IO) {
        val docker = SharedStack.dockerClient
        val exists = docker.listNetworksCmd()
            .withNameFilter(name)
            .exec()
            .any { it.name == name }
        if (!exists) {
            docker.createNetworkCmd()
                .withName(name)
                .exec()
        }
    }

    private suspend fun removeDockerNetwork(name: String) = withContext(Dispatchers.IO) {
        val docker = SharedStack.dockerClient
        docker.listNetworksCmd()
            .withNameFilter(name)
            .exec()
            .filter { it.name == name }
            .forEach {
                runCatching {
                    docker.removeNetworkCmd(it.id)
                        .exec()
                }
            }
    }

    // --- Minecraft protocol VarInt / String helpers ---

    private fun buildPacket(body: (ByteArrayOutputStream) -> Unit): ByteArray {
        val payload = ByteArrayOutputStream()
        body(payload)
        val data = payload.toByteArray()
        val framed = ByteArrayOutputStream()
        writeVarInt(framed, data.size)
        framed.write(data)
        return framed.toByteArray()
    }

    private fun writeVarInt(out: OutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun writeString(out: OutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var position = 0
        while (true) {
            val current = input.read()
            if (current == -1) throw EOFException("stream closed mid-VarInt")
            value = value or ((current and 0x7F) shl position)
            if ((current and 0x80) == 0) break
            position += 7
            if (position >= 32) throw RuntimeException("VarInt too big")
        }
        return value
    }
}
