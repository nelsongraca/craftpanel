package io.craftpanel.agent.grpc

import java.nio.file.Files
import java.nio.file.Path

// Touched on successful auth and on every metrics tick so the healthcheck can tell a live
// gRPC stream from a JVM stuck in ConnectionManager's reconnect/backoff loop.
object Heartbeat {

    private val path: Path = Path.of(System.getenv("HEARTBEAT_FILE") ?: "/tmp/agent-heartbeat")

    fun beat() {
        runCatching { Files.writeString(path, System.currentTimeMillis().toString()) }
    }
}
