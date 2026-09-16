package com.mixion.protocoltest.core.transport

object HexUtil {

    fun toHexString(bytes: ByteArray, separator: String = " "): String {
        val sb = StringBuilder()
        for (i in bytes.indices) {
            sb.append(String.format("%02X", bytes[i]))
            if (i < bytes.size - 1) {
                sb.append(separator)
            }
        }
        return sb.toString()
    }

    fun toHexString(str: String, separator: String = " "): String {
        return toHexString(str.toByteArray(Charsets.UTF_8), separator)
    }

    fun formatAsciiPreview(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c in ' '..'~') {
                sb.append(c)
            } else if (c == '\n') {
                sb.append("\\n\n")
            } else if (c == '\r') {
                sb.append("\\r")
            } else if (c == '\t') {
                sb.append("\\t")
            } else {
                sb.append(".")
            }
        }
        return sb.toString()
    }
}
