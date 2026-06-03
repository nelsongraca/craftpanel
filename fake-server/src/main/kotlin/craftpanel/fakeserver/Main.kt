package craftpanel.fakeserver

import kotlinx.coroutines.*

fun main() {
    val config = Config.fromEnv()
    log("craftpanel-fake-server starting")
    log("  TCP/UDP game port : ${config.gamePort}")
    log("  Server name       : ${config.serverName}")
    log("  Max players       : ${config.maxPlayers}")
    log("  Online players    : ${config.onlinePlayers.joinToString()}")
    log("  Stop command      : \"${config.stopCommand}\"")
    log("Health determined by Docker HEALTHCHECK via mc-monitor status (TCP ping)")

    runBlocking {
        val jobs = listOf(
            launch(Dispatchers.IO) { TcpPingServer(config).start() },
            launch(Dispatchers.IO) { UdpQueryServer(config).start() },
            launch(Dispatchers.IO) { StdinListener(config).start(this@runBlocking) },
        )
        jobs.joinAll()
    }
}

fun log(message: String) {
    println("[fake-server] $message")
    System.out.flush()
}
