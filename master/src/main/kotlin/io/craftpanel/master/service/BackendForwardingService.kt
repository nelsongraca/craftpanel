package io.craftpanel.master.service

import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.crypto.ForwardingSecretCipher
import io.craftpanel.master.service.repo.EnvVarsRepository
import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

data class BackendWarning(val backendId: Uuid, val reason: String)

class BackendForwardingService(
    private val serverRepository: ServerRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val cipher: ForwardingSecretCipher,
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

        val backends = proxyBackendRepository.listProxyBackends(proxyServerId)
        val warnings = mutableListOf<BackendWarning>()

        for (backend in backends) {
            val backendRow = serverRepository.findById(backend.backendServerId)
                ?: continue
            if (backendRow.configMode == "MANUAL") {
                warnings.add(BackendWarning(backend.backendServerId, "Server is in MANUAL config mode — forwarding config not applied; configure it by hand"))
                continue
            }

            val classification = BackendForwarding.classify(backendRow.serverType, mode)
            when (classification) {
                is Classification.WarnSkip -> {
                    warnings.add(BackendWarning(backend.backendServerId, classification.reason))
                }

                is Classification.Eligible -> {
                    val secret = mintOrReadSecret(proxyServerId)
                    val patchJson = BackendForwardingRenderer.render(classification.file, secret)
                    writeFile(backend.backendServerId, patchFileName(classification.file), patchJson.toByteArray())
                    transaction {
                        val existingOnline = EnvVar.find { (ServerEnvVars.serverId eq backend.backendServerId) and (ServerEnvVars.key eq "ONLINE_MODE") }.firstOrNull()
                        if (existingOnline != null) existingOnline.value = "false" else EnvVar.new { this.serverId = EntityID(backend.backendServerId, Servers); key = "ONLINE_MODE"; value = "false" }
                        val existingPatch = EnvVar.find { (ServerEnvVars.serverId eq backend.backendServerId) and (ServerEnvVars.key eq "PATCH_DEFINITIONS") }.firstOrNull()
                        if (existingPatch != null) existingPatch.value = patchFileEnvValue(classification.file) else EnvVar.new { this.serverId = EntityID(backend.backendServerId, Servers); key = "PATCH_DEFINITIONS"; value = patchFileEnvValue(classification.file) }
                        Server.findById(backend.backendServerId)?.let { it.needsRecreate = true }
                    }
                }
            }
        }
        return warnings
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
        serverRepository.updateForwardingSecret(proxyServerId, enc)
        return plain
    }

    private fun patchFileName(file: String): String {
        val name = file.substringAfterLast('/')
        return "craftpanel-$name"
    }

    private fun patchFileEnvValue(file: String): String {
        val name = patchFileName(file)
        return "/data/$name"
    }

    companion object {
        fun generateSecret(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(32)
        }
    }
}
