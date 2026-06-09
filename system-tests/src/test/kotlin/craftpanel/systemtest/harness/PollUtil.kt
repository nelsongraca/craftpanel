package craftpanel.systemtest.harness

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

suspend fun <T> pollUntilNotNull(
    timeoutMs: Long,
    initialIntervalMs: Long = 100,
    maxIntervalMs: Long = 1000,
    block: suspend () -> T?,
): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var interval = initialIntervalMs
    while (System.currentTimeMillis() < deadline) {
        block()?.let { return it }
        val jitter = Random.nextLong(-(interval / 5), interval / 5 + 1)
        delay((interval + jitter).coerceAtLeast(50).milliseconds)
        interval = (interval * 1.5).toLong()
            .coerceAtMost(maxIntervalMs)
    }
    return null
}
