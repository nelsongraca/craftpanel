package io.craftpanel.master.grpc.handlers

import com.google.protobuf.Timestamp
import io.craftpanel.master.domain.AgentEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.Logger
import kotlin.time.Clock
import kotlin.time.Instant

/** Use the proto's recorded-at timestamp when it is present, else the current time. */
internal fun recordedAtOrNow(hasRecordedAt: Boolean, recordedAt: Timestamp): Instant =
    if (hasRecordedAt) {
        Instant.fromEpochSeconds(recordedAt.seconds, recordedAt.nanos.toLong())
    } else {
        Clock.System.now()
    }

/**
 * Emit a telemetry event without suspending the control-stream collector: a full buffer drops the
 * sample rather than delaying console I/O that shares the stream. [subject] identifies the server
 * or node in the drop log.
 */
internal fun MutableSharedFlow<AgentEvent>.tryEmitTelemetry(event: AgentEvent, log: Logger, subject: String) {
    if (!tryEmit(event)) log.debug("Dropped telemetry event for {} — agent event buffer full", subject)
}
