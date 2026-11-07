package com.almus.ytdownloader.browser

/** Where the list of sites with JavaScript turned on is kept. */
interface SiteJsStore {
    fun load(): Set<String>

    fun save(hosts: Set<String>)
}

/**
 * JavaScript is OFF everywhere by default. The user can turn it on for one site (one exact host)
 * at a time, and off again. Subdomains are separate sites: allowing example.com does not allow
 * ads.example.com. Pure Kotlin apart from the store, so it can be unit tested on the JVM.
 */
class JavaScriptPolicy(private val store: SiteJsStore) {
    private var hosts: Set<String> = store.load().mapNotNull { normalise(it) }.toSet()

    fun isAllowed(host: String?): Boolean {
        val key = normalise(host) ?: return false
        return key in hosts
    }

    /** Returns false (and changes nothing) when [host] is not a usable host or the list is full. */
    fun setAllowed(host: String?, allowed: Boolean): Boolean {
        val key = normalise(host) ?: return false
        val updated = if (allowed) {
            if (key !in hosts && hosts.size >= MAX_HOSTS) return false
            hosts + key
        } else {
            hosts - key
        }
        hosts = updated
        store.save(updated)
        return true
    }

    fun allowedHosts(): Set<String> = hosts

    fun clearAll() {
        hosts = emptySet()
        store.save(hosts)
    }

    private fun normalise(host: String?): String? {
        val key = host?.trim()?.lowercase()?.trimEnd('.') ?: return null
        if (key.isEmpty() || key.length > MAX_HOST_LENGTH) return null
        if (key.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        return key
    }

    companion object {
        const val MAX_HOSTS = 200
        private const val MAX_HOST_LENGTH = 253
    }
}
