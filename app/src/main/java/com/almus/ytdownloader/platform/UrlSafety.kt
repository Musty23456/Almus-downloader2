package com.almus.ytdownloader.platform

/**
 * Text-only address checks. No name lookup, no connection: a host is judged by how it is written.
 * Pure Kotlin, unit tested.
 */
object UrlSafety {
    /** True when [host] is written as a name or number that only works on a local network (or hides one). */
    fun isLocalOrPrivate(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return true
        if (h.startsWith("[")) return isPrivateIpv6(h.removePrefix("[").removeSuffix("]"))
        if (h.all { it.isDigit() || it == '.' }) {
            val ip = parseIpv4(h) ?: return true // "127.1", "2130706433": numeric disguises of an address
            return isPrivateIpv4(ip)
        }
        if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        return !h.contains('.') // a single word such as "nas" resolves only on a local network
    }

    fun hasPunycode(host: String): Boolean = host.lowercase().split('.').any { it.startsWith("xn--") }

    /** True when [host] equals [domain] or is a sub-domain of it. "youtube.com.evil.com" is NOT a sub-domain of youtube.com. */
    fun hostIs(host: String, domain: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == domain || h.endsWith(".$domain")
    }

    /** Host written in lower case, common digit-for-letter swaps undone and separators removed, for look-alike checks. */
    fun squash(host: String): String {
        val sb = StringBuilder()
        for (ch in host.lowercase()) {
            when (ch) {
                '.', '-', '_' -> Unit
                '0' -> sb.append('o')
                '1' -> sb.append('l')
                '3' -> sb.append('e')
                '4' -> sb.append('a')
                '5' -> sb.append('s')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".intranet", ".home.arpa")

    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return null
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }

    private fun isPrivateIpv4(ip: IntArray): Boolean {
        val a = ip[0]
        val b = ip[1]
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    private fun isPrivateIpv6(text: String): Boolean {
        val t = text.lowercase()
        if (t == "::" || t == "::1") return true
        if (t.startsWith("::ffff:")) {
            val ip = parseIpv4(t.removePrefix("::ffff:")) ?: return true
            return isPrivateIpv4(ip)
        }
        return t.startsWith("fc") || t.startsWith("fd") ||
            t.startsWith("fe8") || t.startsWith("fe9") || t.startsWith("fea") || t.startsWith("feb")
    }
}
