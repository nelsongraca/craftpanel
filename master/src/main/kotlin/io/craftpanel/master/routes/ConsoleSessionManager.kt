package io.craftpanel.master.routes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

internal class ConsoleSession {

    val viewerCount = AtomicInteger(0)
    val input = Channel<ByteArray>(Channel.BUFFERED)
    val output = MutableSharedFlow<ByteArray>(
        replay = 2000,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val closed = MutableStateFlow(false)
    var job: Job? = null
}

internal class ConsoleSessionManager(private val openConsole: (serverId: Uuid, input: Flow<ByteArray>) -> Flow<ByteArray>, private val scope: CoroutineScope) {

    private val log = LoggerFactory.getLogger(ConsoleSessionManager::class.java)
    private val sessions = ConcurrentHashMap<Uuid, ConsoleSession>()

    private fun mutate(serverId: Uuid, transform: (ConsoleSession?) -> ConsoleSession?): ConsoleSession? = sessions.compute(serverId) { _, existing -> transform(existing) }

    fun getOrCreate(serverId: Uuid): ConsoleSession {
        var created = false
        val session = mutate(serverId) { existing ->
            existing?.also { it.viewerCount.incrementAndGet() }
                ?: ConsoleSession().also {
                    it.viewerCount.incrementAndGet()
                    created = true
                }
        }!!

        if (created) {
            session.job = scope.launch {
                try {
                    openConsole(serverId, session.input.receiveAsFlow())
                        .collect { bytes ->
                            session.output.emit(bytes)
                        }
                } catch (e: Exception) {
                    log.warn("Console stream for {} ended: {}", serverId, e.message)
                } finally {
                    removeIfCurrent(serverId, session)
                    session.closed.value = true
                }
            }
        }

        return session
    }

    fun releaseViewer(serverId: Uuid) {
        mutate(serverId) { existing ->
            if (existing != null && existing.viewerCount.decrementAndGet() <= 0) {
                existing.job?.cancel()
                null
            } else {
                existing
            }
        }
    }

    private fun removeIfCurrent(serverId: Uuid, session: ConsoleSession) {
        mutate(serverId) { s -> if (s === session) null else s }
    }
}
