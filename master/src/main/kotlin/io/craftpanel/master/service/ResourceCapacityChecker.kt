package io.craftpanel.master.service

import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRepository
import kotlin.uuid.Uuid

internal enum class CapacityResult {
    Ok,
    InsufficientRam,
    InsufficientCpu
}

internal class ResourceCapacityChecker(private val serverRepository: ServerRepository) {

    fun check(node: NodeRow, excludeServerId: Uuid?, memoryMb: Int, cpuLimitMillicores: Int): CapacityResult {
        val others = serverRepository.listByNodeId(node.id)
            .filter { it.id != excludeServerId }
        val usedRam = others.sumOf { it.memoryMb }
        val usedCpu = others.sumOf { it.cpuLimitMillicores }
        if (usedRam + memoryMb > node.totalRamMb - node.reservedRamMb) return CapacityResult.InsufficientRam
        if (node.totalCpuMillicores > 0 && usedCpu + cpuLimitMillicores > node.totalCpuMillicores - node.reservedCpuMillicores) return CapacityResult.InsufficientCpu
        return CapacityResult.Ok
    }
}
