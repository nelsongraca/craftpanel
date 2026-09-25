package io.craftpanel.agent.config

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Process-scoped cache of the install-wide [RuntimeSettings], persisted to disk so the convergence
 * loop keeps working across a master outage (and an agent restart during one) using the last
 * accepted snapshot.
 *
 * The loops re-read [current] on every tick, so a live control-stream update changes cadence without
 * restarting anything. Only non-sensitive tuning values are cached here; the Cloudflare API token
 * never leaves master.
 */
class RuntimeSettingsStore(private val file: File) {

    private val log = LoggerFactory.getLogger(RuntimeSettingsStore::class.java)

    @Volatile
    private var settings: RuntimeSettings = RuntimeSettings.DEFAULTS

    fun current(): RuntimeSettings = settings

    /** Reads the disk cache at startup; a missing or corrupt file falls back to defaults. */
    fun load() {
        if (!file.exists()) {
            log.info("No runtime-settings cache at {} — using defaults", file.path)
            return
        }
        val loaded = runCatching { file.readText() }
            .onFailure { log.warn("Could not read {} — using defaults: {}", file.path, it.message) }
            .getOrNull()
            ?.let { RuntimeSettings.decode(it) }
        if (loaded == null) {
            log.warn("Runtime-settings cache at {} is unreadable — using defaults", file.path)
            return
        }
        settings = loaded
        log.info("Loaded runtime settings from {}: {}", file.path, loaded)
    }

    /** Applies a live snapshot and persists it atomically. A write failure is logged, never fatal. */
    fun apply(next: RuntimeSettings) {
        settings = next
        runCatching { persist(next) }
            .onFailure { log.warn("Could not persist runtime settings to {}: {}", file.path, it.message) }
    }

    private fun persist(next: RuntimeSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(RuntimeSettings.encode(next))
        runCatching {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            // ATOMIC_MOVE can be unsupported across filesystems; fall back to a plain replace.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
