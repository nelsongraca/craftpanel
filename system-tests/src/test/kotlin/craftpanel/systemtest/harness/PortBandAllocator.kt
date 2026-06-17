package craftpanel.systemtest.harness

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * JVM-wide allocator for non-overlapping 500-wide port bands used by test nodes.
 *
 * Bands live in the window [WINDOW_START, WINDOW_END) carved into [SLOT_COUNT]
 * 1000-port slots (500-wide band + 500 gap). A random starting slot per JVM run
 * avoids collisions with leftover containers from a previous run that may still
 * hold ports in the low part of the window. Slots wrap modulo [SLOT_COUNT] so the
 * computed port can never exceed 65535, no matter how many bands are claimed.
 *
 * The window stays well below 65535: WINDOW_START=30000, SLOT_COUNT=30 →
 * highest band start = 30000 + 29*1000 = 59000, end 59499.
 */
object PortBandAllocator {

    private const val WINDOW_START = 30000
    private const val SLOT_COUNT = 30
    private const val SLOT_WIDTH = 1000
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
