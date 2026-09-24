package io.craftpanel.master.service

import io.craftpanel.master.crypto.SecretCipher
import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.util.CryptoUtils
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

data class BackendWarning(val backendId: Uuid, val reason: String)

class BackendForwardingService(
    private val serverRepository: ServerRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val cipher: SecretCipher,
    private val writeFile: suspend (Uuid, String, ByteArray) -> Unit
) {

    /**
     * Push forwarding config to all eligible backends of the given proxy.
     * Skips the whole fan-out if the proxy itself is MANUAL.
     * Returns warnings for any backends that were skipped.
     */
    suspend fun applyToAllBackends(proxyServerId: Uuid, mode: String): List<BackendWarning> {
        val proxyRow = serverRepository.findById(proxyServerId)
            ?: throw NotFoundException("Proxy server not found")
        if (proxyRow.configMode == "MANUAL") return emptyList()

        // The proxy must carry the same secret the backends receive, or modern forwarding is
        // rejected at the backend (ADR-0003: master writes the secret to both sides).
        ensureProxySecret(proxyServerId, mode)

        val backends = proxyBackendRepository.listProxyBackends(proxyServerId)
        val warnings = mutableListOf<BackendWarning>()

        for (backend in backends) {
            val backendRow = serverRepository.findById(backend.backendServerId)
                ?: continue
            if (backendRow.configMode == "MANUAL") {
                warnings.add(BackendWarning(backend.backendServerId, "Server is in MANUAL config mode — forwarding config not applied; configure it by hand"))
                continue
            }

            when (val classification = BackendForwarding.classify(backendRow.serverType, mode)) {
                is Classification.WarnSkip -> {
                    warnings.add(BackendWarning(backend.backendServerId, classification.reason))
                }

                is Classification.Eligible -> {
                    val secret = mintOrReadSecret(proxyServerId)
                    val patchJson = BackendForwardingRenderer.render(classification.file, secret)
                    writeFile(backend.backendServerId, patchFileName(classification.file), patchJson.toByteArray())
                    transaction {
                        val existingOnline = EnvVar.find { (ServerEnvVars.serverId eq backend.backendServerId) and (ServerEnvVars.key eq "ONLINE_MODE") }.firstOrNull()
                        if (existingOnline != null) {
                            existingOnline.value = "false"
                        } else {
                            EnvVar.new {
                                this.serverId = EntityID(backend.backendServerId, Servers)
                                key = "ONLINE_MODE"
                                value = "false"
                            }
                        }
                        val existingPatch = EnvVar.find { (ServerEnvVars.serverId eq backend.backendServerId) and (ServerEnvVars.key eq "PATCH_DEFINITIONS") }.firstOrNull()
                        existingPatch?.delete()
                        Server.findById(backend.backendServerId)?.let {
                            it.forwardingPatchFile = patchFileContainerPath(classification.file)
                            it.restartPending = true
                        }
                    }
                }
            }
        }
        return warnings
    }

    /**
     * Write the master-minted secret into the proxy's own `forwarding.secret` (Velocity modern
     * forwarding only — LEGACY `ip_forward` and BungeeGuard do not read the file). No-op for a
     * non-Velocity or MANUAL proxy. Safe to call on every start: the write is idempotent.
     */
    suspend fun ensureProxySecret(proxyServerId: Uuid, mode: String) {
        if (mode != "MODERN") return
        val proxyRow = serverRepository.findById(proxyServerId)
            ?: throw NotFoundException("Proxy server not found")
        if (proxyRow.configMode == "MANUAL") return
        if (proxyRow.serverType != ServerType.VELOCITY) return
        val secret = mintOrReadSecret(proxyServerId)
        writeFile(proxyServerId, PROXY_SECRET_FILENAME, secret.toByteArray())
    }

    /**
     * Read the existing encrypted secret for the proxy, or mint+encrypt+store a new one.
     * Never overwrites a non-null value (rotation = out of scope).
     */
    private fun mintOrReadSecret(proxyServerId: Uuid): String {
        val proxyRow = serverRepository.findById(proxyServerId)
            ?: throw NotFoundException("Proxy server not found")
        val existingEnc = proxyRow.forwardingSecretEnc
        if (existingEnc != null) {
            return cipher.decrypt(existingEnc)
        }
        val plain = generateSecret()
        val enc = cipher.encrypt(plain)
        transaction { Server.findById(proxyServerId)?.let { it.forwardingSecretEnc = enc } }
        return plain
    }

    private fun patchFileName(file: String): String {
        val name = file.substringAfterLast('/')
        return "craftpanel-$name"
    }

    private fun patchFileContainerPath(file: String): String {
        val name = patchFileName(file)
        return "/data/$name"
    }

    companion object {

        /** Velocity's default `forwarding-secret-file`, resolved under the proxy data dir. */
        const val PROXY_SECRET_FILENAME = "forwarding.secret"

        fun generateSecret(): String = CryptoUtils.generateToken(24)
    }
}
