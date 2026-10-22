package dev.saltartist.am

/** Small strict JSON reader for the public Apple page payload; no host JSON dependency. */
internal object AppleMusicJson {
    fun read(text: String): Any? = Reader(text).read()

    private class Reader(private val text: String) {
        private var at = 0

        fun read(): Any? {
            require(text.length <= 12_000_000) { "Apple JSON is too large" }
            val result = value(0)
            whitespace()
            require(at == text.length) { "Trailing JSON content" }
            return result
        }

        private fun value(depth: Int): Any? {
            require(depth < 96) { "Apple JSON nesting is too deep" }
            whitespace()
            require(at < text.length) { "Unexpected JSON end" }
            return when (text[at]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> numberValue()
                else -> error("Unexpected JSON token at $at")
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            at++
            whitespace()
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            do {
                whitespace()
                require(at < text.length && text[at] == '"') { "Expected JSON key" }
                val key = stringValue()
                whitespace()
                require(take(':')) { "Expected JSON colon" }
                result[key] = value(depth)
                whitespace()
                if (take('}')) return result
            } while (take(','))
            error("Expected JSON object end")
        }

        private fun arrayValue(depth: Int): List<Any?> {
            at++
            whitespace()
            val result = arrayListOf<Any?>()
            if (take(']')) return result
            do {
                result += value(depth)
                whitespace()
                if (take(']')) return result
            } while (take(','))
            error("Expected JSON array end")
        }

        private fun stringValue(): String {
            require(take('"'))
            val result = StringBuilder()
            while (at < text.length) {
                val char = text[at++]
                when (char) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(at < text.length) { "Incomplete JSON escape" }
                        when (val escape = text[at++]) {
                            '"', '\\', '/' -> result.append(escape)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(at + 4 <= text.length) { "Incomplete Unicode escape" }
                                result.append(text.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> error("Unknown JSON escape")
                        }
                    }
                    else -> {
                        require(char.code >= 0x20) { "Unescaped JSON control character" }
                        result.append(char)
                    }
                }
            }
            error("Unterminated JSON string")
        }

        private fun numberValue(): Number {
            val start = at
            take('-')
            if (!take('0')) {
                require(at < text.length && text[at] in '1'..'9') { "Invalid JSON number" }
                while (at < text.length && text[at].isDigit()) at++
            }
            if (take('.')) digits()
            if (at < text.length && text[at] in "eE") {
                at++
                if (at < text.length && text[at] in "+-") at++
                digits()
            }
            val raw = text.substring(start, at)
            return raw.toLongOrNull() ?: raw.toDouble().also { require(it.isFinite()) }
        }

        private fun digits() {
            val start = at
            while (at < text.length && text[at].isDigit()) at++
            require(at > start) { "Expected JSON digits" }
        }

        private fun literal(expected: String, value: Any?): Any? {
            require(text.startsWith(expected, at)) { "Invalid JSON literal" }
            at += expected.length
            return value
        }

        private fun take(char: Char): Boolean =
            if (at < text.length && text[at] == char) { at++; true } else false

        private fun whitespace() {
            while (at < text.length && text[at] in " \r\n\t") at++
        }
    }
}

internal fun Any?.jsonObject(): Map<*, *>? = this as? Map<*, *>
internal fun Any?.jsonArray(): List<*> = this as? List<*> ?: emptyList<Any?>()
internal fun Map<*, *>?.objectAt(key: String): Map<*, *>? = this?.get(key).jsonObject()
internal fun Map<*, *>?.textAt(key: String): String? = (this?.get(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
