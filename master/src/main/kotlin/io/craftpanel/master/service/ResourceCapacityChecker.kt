package io.craftpanel.master.service

import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRepository
import kotlin.uuid.Uuid

internal enum class CapacityResult {
    Ok,
    InsufficientRam,
    InsufficientCpu,
}

internal class ResourceCapacityChecker(private val serverRepository: ServerRepository) {

    fun check(node: NodeRow, excludeServerId: Uuid?, memoryMb: Int, cpuShares: Int): CapacityResult {
        val others = serverRepository.listByNodeId(node.id)
            .filter { it.id != excludeServerId }
        val usedRam = others.sumOf { it.memoryMb }
        val usedCpu = others.sumOf { it.cpuShares }
        val effectiveUsedRam = maxOf(usedRam, node.systemRamUsedMb ?: 0)
        if (effectiveUsedRam + memoryMb > node.totalRamMb) return CapacityResult.InsufficientRam
        if (node.totalCpuShares > 0 && usedCpu + cpuShares > node.totalCpuShares) return CapacityResult.InsufficientCpu
        return CapacityResult.Ok
    }
}
