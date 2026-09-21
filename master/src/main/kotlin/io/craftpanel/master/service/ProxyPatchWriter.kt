package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.ServerView
import kotlin.uuid.Uuid

/** The single owner of the proxy patch write: filename, patch generation, and the file write. */
class ProxyPatchWriter(
    private val patchService: ProxyConfigPatchService,
    private val writeFile: suspend (Uuid, String, ByteArray) -> Unit
) {

    /** Ungated: used before start/restart. No-op for non-proxy / manual-mode / no-patch. */
    suspend fun write(server: ServerView) {
        if (!server.serverType.isProxy) return
        val patch = patchService.generatePatch(server.id) ?: return
        writeFile(server.id, PATCH_FILENAME, patch.toByteArray())
    }

    /** Gated: writes only when the server is reported HEALTHY. */
    suspend fun writeIfRunning(server: ServerView) {
        if (ServerStatus.fromDb(server.status) != ServerStatus.HEALTHY) return
        write(server)
    }

    private companion object {

        const val PATCH_FILENAME = "craftpanel-patch.json"
    }
}
