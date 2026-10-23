package com.osint.unified.domain

import android.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Date

/** CyberChef-style on-device data transforms. Pure, synchronous, no network. */
object DecoderEngine {

    data class Op(val id: String, val label: String)

    val ops: List<Op> = listOf(
        Op("b64d", "Base64 decode"),
        Op("b64e", "Base64 encode"),
        Op("hexd", "Hex decode"),
        Op("hexe", "Hex encode"),
        Op("urld", "URL decode"),
        Op("urle", "URL encode"),
        Op("rot13", "ROT13"),
        Op("md5", "MD5"),
        Op("sha1", "SHA-1"),
        Op("sha256", "SHA-256"),
        Op("sha512", "SHA-512"),
        Op("jwt", "JWT decode"),
        Op("bin", "To binary"),
        Op("rev", "Reverse"),
        Op("ts", "Unix time")
    )

    fun run(opId: String, input: String): String = try {
        when (opId) {
            // Both alphabets.
            //
            // Base64.DEFAULT rejects - and _, and the identifier
            // screen offers this very operation for a string it has
            // just labelled "Base64url — uses - and _ in place of +
            // and /". Tapping the decoder it recommended answered
            // "Error: bad base-64". The JWT branch below has done the
            // translation all along.
            "b64d" -> String(
                Base64.decode(
                    input.trim().replace('-', '+').replace('_', '/')
                        .let { it + "=".repeat((4 - it.length % 4) % 4) },
                    Base64.DEFAULT
                )
            )
            "b64e" -> Base64.encodeToString(input.toByteArray(), Base64.NO_WRAP)
            "hexd" -> hexDecode(input)
            "hexe" -> input.toByteArray().joinToString("") { "%02x".format(it) }
            "urld" -> URLDecoder.decode(input, "UTF-8")
            "urle" -> URLEncoder.encode(input, "UTF-8")
            "rot13" -> input.map { c ->
                when (c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                }
            }.joinToString("")
            "md5" -> hash("MD5", input)
            "sha1" -> hash("SHA-1", input)
            "sha256" -> hash("SHA-256", input)
            "sha512" -> hash("SHA-512", input)
            "jwt" -> jwtDecode(input)
            "bin" -> input.toByteArray().joinToString(" ") {
                (it.toInt() and 0xFF).toString(2).padStart(8, '0')
            }
            "rev" -> input.reversed()
            "ts" -> parseTimestamp(input)
            else -> "Unknown operation"
        }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    private fun hash(algo: String, input: String): String =
        MessageDigest.getInstance(algo).digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Hex, with only the punctuation stripped.
     *
     * This used to strip everything that was not a hex digit, which
     * also keeps any a-f that happened to be prose: pasting
     * "MD5: 5d41402abc4b2a76b9719d911017c592" kept the D of MD5 as a
     * leading nibble, so every byte after it was shifted by half a
     * byte and the output was entirely wrong with no complaint. An
     * odd number of digits was silently truncated for the same
     * reason — "0xdeadbeef" dropped the f.
     */
    private fun hexDecode(input: String): String {
        val clean = input.trim()
            .removePrefix("0x").removePrefix("0X")
            .replace(Regex("[\\s:,\\-]+"), "")
        if (clean.isEmpty()) return "(no hex bytes)"
        if (!clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return "Not hex: remove anything that is not a hex digit " +
                "(spaces, colons, dashes and a leading 0x are fine)."
        }
        if (clean.length % 2 != 0) {
            return "An odd number of hex digits (${clean.length}) — a byte is two."
        }
        val bytes = ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) +
                Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
        return String(bytes)
    }

    private fun jwtDecode(input: String): String {
        val parts = input.trim().split(".")
        if (parts.size < 2) return "Not a JWT (expected header.payload.signature)"
        fun b64url(s: String): String {
            val padded = s.replace('-', '+').replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            return String(Base64.decode(padded, Base64.DEFAULT))
        }
        return "Header: ${b64url(parts[0])}\nPayload: ${b64url(parts[1])}"
    }

    private fun parseTimestamp(input: String): String {
        val n = input.trim().toLongOrNull() ?: return "Not a number"
        val millis = if (n > 9_999_999_999L) n else n * 1000
        return Date(millis).toString()
    }
}
