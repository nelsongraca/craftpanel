package io.craftpanel.master.dns

import io.craftpanel.master.config.DnsConfig
import io.craftpanel.master.crypto.SecretCipher
import io.craftpanel.master.service.EncryptedSettingValue
import io.craftpanel.master.service.SettingsProvider
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Lazily builds and caches the optional [DnsProvider] from database-backed settings.
 *
 * The provider is built only when DNS is first used, so a missing or rejected token can never make
 * master unbootable. The cache is keyed on `(provider, encrypted token)`: any other settings change
 * leaves the provider untouched, while a provider or token change rebuilds it. [refresh] is called
 * after a settings write and — on failure — keeps the previously active provider so an operator typo
 * does not break DNS management.
 */
class DnsProviderResolver(
    private val settingsProvider: SettingsProvider,
    private val cipher: SecretCipher,
    private val factory: (DnsConfig) -> DnsProvider? = DnsProviderFactory::create
) {

    private val log = LoggerFactory.getLogger(DnsProviderResolver::class.java)

    private data class CacheKey(val provider: String, val encryptedToken: String?)

    private data class Cached(val key: CacheKey, val provider: DnsProvider?)

    @Volatile
    private var cached: Cached? = null

    /** The current provider, rebuilt when the provider/token pair changed since the last build. */
    fun current(): DnsProvider? {
        val key = key()
        cached?.let { if (it.key == key) return it.provider }
        return rebuild(key)
    }

    /** Rebuilds from the current settings. Keeps the previous provider when the rebuild throws. */
    fun refresh() {
        rebuild(key())
    }

    private fun key(): CacheKey =
        CacheKey(settingsProvider.current().dnsProvider, settingsProvider.encryptedCfApiToken())

    @Synchronized
    private fun rebuild(key: CacheKey): DnsProvider? {
        val token = key.encryptedToken?.let { EncryptedSettingValue.decrypt(cipher, it) } ?: ""
        return try {
            val provider = factory(DnsConfig(key.provider, token))
            cached = Cached(key, provider)
            provider
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            log.error("Failed to build DNS provider '{}' — keeping the previous provider", key.provider, e)
            cached?.provider
        }
    }
}
