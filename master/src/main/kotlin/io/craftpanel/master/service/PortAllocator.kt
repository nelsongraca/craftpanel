package io.craftpanel.master.service

internal object PortAllocator {

    fun pickFreePort(portRangeStart: Int, portRangeEnd: Int, usedPorts: Set<Int>): Int? = (portRangeStart..portRangeEnd).firstOrNull { it !in usedPorts }
}
