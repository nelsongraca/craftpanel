package io.craftpanel.master.grpc

import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.ContainerState

fun mapContainerState(runState: ContainerState.RunState, dbStatus: ServerStatus): ServerStatus? = when {
    runState == ContainerState.RunState.RUNNING && dbStatus != ServerStatus.HEALTHY  -> ServerStatus.HEALTHY
    runState == ContainerState.RunState.STOPPED && dbStatus.isRunning                -> ServerStatus.STOPPED
    runState == ContainerState.RunState.EXITED && dbStatus != ServerStatus.UNHEALTHY -> ServerStatus.UNHEALTHY
    else                                                                             -> null
}

fun mapMissingContainer(dbStatus: ServerStatus): ServerStatus? =
    if (dbStatus != ServerStatus.STOPPED) ServerStatus.STOPPED else null
