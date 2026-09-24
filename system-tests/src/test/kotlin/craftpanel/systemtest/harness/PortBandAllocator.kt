package craftpanel.systemtest.harness

import craftpanel.systemtest.harness.PortBandAllocator.SLOT_COUNT
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * JVM-wide allocator for non-overlapping 500-wide port bands used by test nodes.
 *
 * Bands live in the window [WINDOW_START, WINDOW_END) carved into [SLOT_COUNT]
 * 550-port slots (499-wide band + 51 gap). A random starting slot per JVM run
 * avoids collisions with leftover containers from a previous run that may still
 * hold ports in the low part of the window. Slots wrap modulo [SLOT_COUNT] so the
 * computed port can never exceed 65535, no matter how many bands are claimed.
 *
 * The window must stay **below the kernel's ephemeral port range** (32768-60999 by
 * default on Linux). Outbound connections — e.g. an agent's long-lived gRPC stream to
 * master — take a source port from that range, and binding a band port that a live
 * connection already holds fails with EADDRINUSE. It must also avoid 25565, which
 * mc-router always binds on the host. 10000-25349 satisfies both.
 */
object PortBandAllocator {

    private const val WINDOW_START = 10000
    private const val SLOT_COUNT = 28
    private const val SLOT_WIDTH = 550
    private const val BAND_WIDTH = 499

    // Random starting slot per JVM run; subsequent calls advance and wrap.
    private val startSlot = Random.nextInt(0, SLOT_COUNT)
    private val counter = AtomicInteger(0)

    /** Returns the next (portStart, portEnd) pair for a 500-wide band. */
    fun next(): Pair<Int, Int> {
        val slot = (startSlot + counter.getAndAdd(1)) % SLOT_COUNT
        val portStart = WINDOW_START + slot * SLOT_WIDTH
        val portEnd = portStart + BAND_WIDTH
        return portStart to portEnd
    }
}
