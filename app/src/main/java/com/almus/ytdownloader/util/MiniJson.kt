package com.almus.ytdownloader.util

/** Raised internally when a text is not valid JSON. Never escapes [MiniJson]. */
internal class JsonException(message: String) : Exception(message)

/**
 * A small, strict JSON reader for the few official YouTube answers this module reads.
 * It exists so the module stays plain Kotlin (testable on the JVM, no extra dependency).
 * Objects become Map<String, Any?>, arrays List<Any?>, numbers Double. Size and depth are bounded.
 */
object MiniJson {
    const val MAX_CHARS = 512 * 1024
    const val MAX_DEPTH = 32

    /** The top-level object of [text], or null when [text] is not a valid JSON object. */
    fun parseObject(text: String): Map<String, Any?>? = try {
        asObject(Parser(text).parseRoot())
    } catch (_: JsonException) {
        null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseRoot(): Any? {
            if (s.length > MAX_CHARS) throw JsonException("too large")
            val v = value(0)
            skipWs()
            if (i != s.length) throw JsonException("trailing data")
            return v
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun peek(): Char {
            if (i >= s.length) throw JsonException("unexpected end")
            return s[i]
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("too deep")
            skipWs()
            val c = peek()
            return when {
                c == '{' -> obj(depth)
                c == '[' -> arr(depth)
                c == '"' -> str()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> num()
                else -> throw JsonException("unexpected character")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("bad literal")
            i += word.length
            return result
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                i++
                return map
            }
            while (i < s.length) {
                skipWs()
                if (peek() != '"') throw JsonException("expected a key")
                val key = str()
                skipWs()
                if (peek() != ':') throw JsonException("expected ':'")
                i++
                map[key] = value(depth + 1)
                skipWs()
                val c = peek()
                i++
                if (c == '}') return map
                if (c != ',') throw JsonException("expected ',' or '}'")
            }
            throw JsonException("unterminated object")
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                i++
                return list
            }
            while (i < s.length) {
                list.add(value(depth + 1))
                skipWs()
                val c = peek()
                i++
                if (c == ']') return list
                if (c != ',') throw JsonException("expected ',' or ']'")
            }
            throw JsonException("unterminated array")
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonException("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw JsonException("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonException("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw JsonException("unterminated string")
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw JsonException("bad number")
        }
    }
}

internal fun asObject(value: Any?): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return value as? Map<String, Any?>
}

internal fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = asObject(this[key])

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

internal fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<*>
