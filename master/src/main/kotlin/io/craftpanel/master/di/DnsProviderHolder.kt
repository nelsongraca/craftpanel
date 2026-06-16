package io.craftpanel.master.di

import io.craftpanel.master.dns.DnsProvider

/**
 * Wraps the optional [DnsProvider] so Koin can hold it as a non-null singleton.
 * Koin's `single {}` rejects a lambda that returns null ("Single instance created
 * couldn't return value"), so the nullable provider is carried inside this holder.
 */
class DnsProviderHolder(val provider: DnsProvider?)
