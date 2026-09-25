package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.RuntimeSettings
import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.proto.AgentRuntimeSettingsUpdate
import org.slf4j.LoggerFactory

/**
 * Applies a live install-wide runtime settings change pushed by master. The store is process-scoped,
 * so the convergence and metrics loops pick the new values up on their next tick.
 */
class RuntimeSettingsHandler(private val store: RuntimeSettingsStore) {

    private val log = LoggerFactory.getLogger(RuntimeSettingsHandler::class.java)

    fun handle(update: AgentRuntimeSettingsUpdate) {
        val settings = RuntimeSettings.fromProto(update.settings) ?: run {
            log.warn("Ignoring all-zero runtime settings snapshot")
            return
        }
        store.apply(settings)
        log.info("Applied live runtime settings update: {}", settings)
    }
}
