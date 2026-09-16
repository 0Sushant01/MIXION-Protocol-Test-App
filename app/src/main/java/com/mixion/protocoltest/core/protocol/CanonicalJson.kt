package com.mixion.protocoltest.core.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * Implements Section 8.1 Canonicalization Rules:
 * - Compact JSON (no extraneous whitespace)
 * - Object keys sorted lexicographically ascending recursively
 * - Arrays preserve original element order
 * - Numbers: duration_ms and version as positive integers, no scientific notation or floats
 * - Excludes crc32 during CRC calculation, then inserts crc32 into canonical position
 * - Appends LF (\n) as NDJSON frame delimiter
 */
object CanonicalJson {

    /**
     * Converts a raw JSON string or JSON Object into its canonical form.
     * If excludeCrc is true, any "crc32" key at root level is omitted.
     */
    fun canonicalize(rawJson: String, excludeCrc: Boolean = false): String {
        val trimmed = rawJson.trim()
        if (trimmed.startsWith("{")) {
            val jsonObject = JSONObject(trimmed)
            return canonicalize(jsonObject, excludeCrc)
        } else if (trimmed.startsWith("[")) {
            val jsonArray = JSONArray(trimmed)
            return canonicalize(jsonArray)
        }
        return trimmed
    }

    /**
     * Canonicalizes a JSONObject recursively.
     */
    fun canonicalize(obj: JSONObject, excludeCrc: Boolean = false): String {
        val sortedKeys = TreeMap<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            if (excludeCrc && key == "crc32") {
                continue
            }
            sortedKeys[key] = obj.get(key)
        }

        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in sortedKeys) {
            if (!first) {
                sb.append(",")
            }
            first = false
            sb.append(escapeString(key))
            sb.append(":")
            sb.append(canonicalizeValue(value))
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * Canonicalizes a JSONArray recursively.
     */
    fun canonicalize(arr: JSONArray): String {
        val sb = StringBuilder("[")
        for (i in 0 until arr.length()) {
            if (i > 0) {
                sb.append(",")
            }
            sb.append(canonicalizeValue(arr.get(i)))
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * Formats an arbitrary value canonically.
     */
    private fun canonicalizeValue(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> canonicalize(value, false)
            is JSONArray -> canonicalize(value)
            is String -> escapeString(value)
            is Boolean -> if (value) "true" else "false"
            is Number -> formatNumber(value)
            is Map<*, *> -> {
                val json = JSONObject()
                for ((k, v) in value) {
                    if (k != null) json.put(k.toString(), v)
                }
                canonicalize(json, false)
            }
            is Collection<*> -> {
                val json = JSONArray()
                for (item in value) {
                    json.put(item)
                }
                canonicalize(json)
            }
            else -> escapeString(value.toString())
        }
    }

    private fun formatNumber(number: Number): String {
        return when (number) {
            is Long, is Int, is Short, is Byte -> number.toString()
            is Double, is Float -> {
                val d = number.toDouble()
                if (d == d.toLong().toDouble()) {
                    d.toLong().toString()
                } else {
                    d.toString()
                }
            }
            else -> number.toString()
        }
    }

    /**
     * Escapes a string to conform with strict JSON syntax.
     */
    fun escapeString(str: String): String {
        val sb = StringBuilder("\"")
        for (c in str) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code in 0x00..0x1F) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * Builds a full NDJSON frame according to Section 8.2:
     * 1. Canonicalize without crc32
     * 2. Calculate CRC32 over UTF-8 bytes
     * 3. Insert crc32 field into object
     * 4. Canonicalize final JSON (ordering keys lexicographically including crc32)
     * 5. Append single LF (\n)
     */
    fun createSignedFrame(json: JSONObject): String {
        // Step 1: Canonicalize without crc32
        val canonicalWithoutCrc = canonicalize(json, excludeCrc = true)

        // Step 2: Calculate CRC32 over UTF-8 bytes
        val crc = Crc32Util.calculate(canonicalWithoutCrc)

        // Step 3: Insert crc32
        json.put("crc32", crc)

        // Step 4: Final canonical JSON with crc32
        val finalCanonical = canonicalize(json, excludeCrc = false)

        // Step 5: Append LF
        return finalCanonical + "\n"
    }

    /**
     * Verifies the CRC on a received frame or JSON string.
     */
    fun verifyCrc(jsonString: String): CrcVerificationResult {
        val clean = jsonString.trim().removeSuffix("\n").removeSuffix("\r")
        val json = try {
            JSONObject(clean)
        } catch (e: Exception) {
            return CrcVerificationResult(
                isValid = false,
                receivedCrc = null,
                calculatedCrc = null,
                canonicalJson = null,
                errorMessage = "Invalid JSON: ${e.message}"
            )
        }

        if (!json.has("crc32")) {
            return CrcVerificationResult(
                isValid = false,
                receivedCrc = null,
                calculatedCrc = null,
                canonicalJson = null,
                errorMessage = "Missing 'crc32' field"
            )
        }

        val receivedCrc = json.getString("crc32").uppercase()
        val canonicalWithoutCrc = canonicalize(json, excludeCrc = true)
        val calculatedCrc = Crc32Util.calculate(canonicalWithoutCrc)
        val matches = calculatedCrc.equals(receivedCrc, ignoreCase = true)

        return CrcVerificationResult(
            isValid = matches,
            receivedCrc = receivedCrc,
            calculatedCrc = calculatedCrc,
            canonicalJson = canonicalWithoutCrc,
            errorMessage = if (matches) null else "CRC mismatch: expected $calculatedCrc, received $receivedCrc"
        )
    }
}

data class CrcVerificationResult(
    val isValid: Boolean,
    val receivedCrc: String?,
    val calculatedCrc: String?,
    val canonicalJson: String?,
    val errorMessage: String?
)
