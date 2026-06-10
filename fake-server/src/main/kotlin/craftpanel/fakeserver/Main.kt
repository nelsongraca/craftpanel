package craftpanel.fakeserver

import java.io.File
import kotlinx.coroutines.*

fun main() {
    val config = Config.fromEnv()
    log("craftpanel-fake-server starting")

    File("/data/server.properties").takeIf { !it.exists() }
        ?.writeText(
            "level-name=world\nmotd=${config.serverName}\nserver-port=${config.gamePort}\nmax-players=${config.maxPlayers}\nonline-mode=false\n"
        )
    log("  TCP/UDP game port : ${config.gamePort}")
    log("  Server name       : ${config.serverName}")
    log("  Max players       : ${config.maxPlayers}")
    log("  Online players    : ${config.onlinePlayers.joinToString()}")
    log("  Stop command      : \"${config.stopCommand}\"")
    log("Health determined by Docker HEALTHCHECK via mc-monitor status (TCP ping)")

    var topScope: CoroutineScope? = null

    Runtime.getRuntime().addShutdownHook(Thread {
        topScope?.let { s ->
            shutdown(s)
            log("shutdown complete")
        }
    })

    runBlocking {
        topScope = this
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
