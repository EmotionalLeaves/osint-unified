package com.osint.unified.domain

import java.util.Locale

/**
 * "What am I looking at?"
 *
 * The decoder panel assumes you already know — you pick Base64, or hex, or
 * JWT. In practice an analyst has a string pulled out of a URL, a header or
 * a paste and does not know which it is, and trying fifteen buttons to find
 * out is the whole job done by hand.
 *
 * So this recognises the shape first and says what the string *is*, then
 * leaves the decoding to [DecoderEngine]. Recognition is structural — length,
 * alphabet, checksum, magic bytes — never a guess dressed up as a fact, and
 * where a string is ambiguous every reading is offered rather than one
 * chosen for you.
 */
object Identify {

    /**
     * One reading of the input. [decoderId] names the [DecoderEngine]
     * operation that would act on it, when there is one.
     */
    data class Guess(
        val label: String,
        val detail: String,
        val decoderId: String? = null,
        val confidence: Int = 50
    )

    private val HEX = Regex("^[0-9a-fA-F]+$")
    private val B64 = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val B64URL = Regex("^[A-Za-z0-9_-]+$")
    // Three non-empty base64url segments whose first begins "ey" — which is
    // base64 for the `{"` that opens every JWT header. Without that test
    // "www.example.com" satisfied the shape and was reported as a JWT above
    // the correct answer, for the single most common input this screen sees.
    private val JWT = Regex("^ey[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*$")
    private val IPV4 = Regex("^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
    // Either a compressed form containing "::", or the full eight hextets.
    // The looser shape accepted "12:34:56", so an ordinary clock time was
    // reported as an address.
    private val IPV6 = Regex(
        // Full form: eight hextets.
        "^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$" +
            // Compressed form: one "::" with up to seven hextets around it.
            "|^(?:[0-9a-fA-F]{1,4})?(?::[0-9a-fA-F]{1,4}){0,6}" +
            "::(?:[0-9a-fA-F]{1,4})?(?::[0-9a-fA-F]{1,4}){0,6}$"
    )

    /**
     * The shape is not enough: the hextets have to add up.
     *
     * The compressed branch allows up to seven groups on each side of
     * "::", so "1:2:3:4:5:6:7::8" matched — eight explicit groups plus
     * a "::" that must stand for at least one more — and so did a
     * thirteen-group string. Both were reported as an IPv6 address at
     * confidence 85.
     */
    internal fun looksLikeIpv6(v: String): Boolean {
        if (!IPV6.matches(v)) return false
        val compressed = v.contains("::")
        val groups = v.split(":").count { it.isNotEmpty() }
        return if (compressed) groups <= 7 else groups == 8
    }
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,24}$")
    private val DOMAIN = Regex("^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[A-Za-z]{2,24}$")
    private val COORD = Regex("^(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)$")
    private val BTC = Regex("^(?:bc1[a-z0-9]{25,62}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$")
    private val ETH = Regex("^0x[a-fA-F0-9]{40}$")
    private val ICAO24 = Regex("^[0-9A-Fa-f]{6}$")
    private val MAC = Regex("^(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val PHONE = Regex("^\\+\\d[\\d\\s().-]{6,20}$")
    private val MGRS = Regex("^\\d{1,2}[C-HJ-NP-X]\\s*[A-HJ-NP-Z]{2}\\s*\\d{2,10}$", RegexOption.IGNORE_CASE)
    private val PEM = Regex("-----BEGIN ([A-Z ]+)-----")

    /** Hashes are told apart by length alone, which is all their hex gives. */
    private val HASH_LENGTHS = mapOf(
        32 to "MD5 or NTLM",
        40 to "SHA-1",
        56 to "SHA-224",
        64 to "SHA-256",
        96 to "SHA-384",
        128 to "SHA-512"
    )

    fun of(raw: String): List<Guess> {
        val v = raw.trim()
        if (v.isEmpty()) return emptyList()
        val out = ArrayList<Guess>()

        PEM.find(v)?.let {
            out.add(Guess("PEM block", "A ${it.groupValues[1].lowercase()} in PEM armour.", confidence = 97))
        }

        if (JWT.matches(v)) {
            out.add(Guess("JWT", "Three dot-separated base64url segments.", "jwt", 95))
        }

        if (EMAIL.matches(v)) out.add(Guess("Email address", "Local part and a resolvable-looking domain.", confidence = 95))
        if (IPV4.matches(v)) out.add(Guess("IPv4 address", "Four octets, each in range.", confidence = 97))
        val looksIpv6 = looksLikeIpv6(v)
        if (looksIpv6) {
            out.add(Guess("IPv6 address", "Colon-separated hextets.", confidence = 85))
        }
        if (MAC.matches(v)) out.add(Guess("MAC address", "Six octets; the first three are the vendor OUI.", confidence = 95))
        if (UUID.matches(v)) out.add(Guess("UUID", "8-4-4-4-12 hex. Version ${v[14]}.", confidence = 97))
        if (PHONE.matches(v)) out.add(Guess("Phone number", "E.164-style, with a country prefix.", confidence = 80))
        if (BTC.matches(v)) {
            // The shape, and the checksum where there is one to read.
            //
            // The class header claims recognition is by "length,
            // alphabet, checksum, magic bytes" and no checksum was
            // ever computed, so the genesis address with its last
            // character changed — which no wallet would accept — was
            // reported as a Bitcoin address at confidence 90.
            val checked = base58Check(v)
            out.add(
                when (checked) {
                    true -> Guess(
                        "Bitcoin address",
                        "Base58Check, and the checksum is right.",
                        confidence = 97
                    )
                    false -> Guess(
                        "Bitcoin address (bad checksum)",
                        "The shape is right and the Base58Check digest is not, " +
                            "so this is a typo or an invented address.",
                        confidence = 40
                    )
                    // bech32: a different checksum scheme, not read here.
                    null -> Guess(
                        "Bitcoin address",
                        "bech32 shape; the checksum is not verified.",
                        confidence = 75
                    )
                }
            )
        }
        if (ETH.matches(v)) {
            val mixed = v.drop(2).any { it.isUpperCase() } && v.drop(2).any { it.isLowerCase() }
            out.add(
                Guess(
                    "Ethereum address",
                    if (mixed) {
                        "20 bytes of hex behind 0x. The mixed case is an EIP-55 " +
                            "checksum, which is not verified here."
                    } else {
                        "20 bytes of hex behind 0x. All one case, so there is no " +
                            "EIP-55 checksum to read."
                    },
                    confidence = 90
                )
            )
        }
        if (MGRS.matches(v)) out.add(Guess("MGRS grid reference", "Military grid; a place, not a number.", confidence = 85))

        COORD.find(v)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                out.add(Guess("Coordinate", "Latitude %.5f, longitude %.5f.".format(Locale.US, lat, lon), confidence = 90))
            }
        }

        if (DOMAIN.matches(v) && !EMAIL.matches(v)) {
            out.add(Guess("Domain name", "Dotted labels ending in a plausible TLD.", confidence = 80))
        }

        if (HEX.matches(v)) {
            HASH_LENGTHS[v.length]?.let {
                // A 32-hex string is overwhelmingly a hash, but it is also
                // exactly 16 bytes, so the decode is offered too.
                out.add(Guess("$it hash", "${v.length} hex characters.", confidence = 85))
            }
            if (ICAO24.matches(v)) {
                out.add(Guess("ICAO 24-bit address", "Six hex digits — an aircraft's permanent identifier.", confidence = 60))
            }
            // A string whose length matches a hash is almost certainly one,
            // but it is still decodable bytes, so both readings stand — the
            // hash just ranks first.
            if (v.length % 2 == 0 && v.length >= 4) {
                val isHashLength = HASH_LENGTHS[v.length] != null
                out.add(
                    Guess(
                        "Hex-encoded bytes",
                        if (isHashLength) "${v.length / 2} bytes, if it is not a hash."
                        else "${v.length / 2} bytes.",
                        "hexd",
                        if (isHashLength) 30 else 70
                    )
                )
            }
        }

        // A bare integer in the plausible epoch range is almost always a time.
        v.toLongOrNull()?.let { n ->
            val seconds = n in 100_000_000L..4_000_000_000L
            val millis = n in 100_000_000_000L..4_000_000_000_000L
            if (seconds || millis) {
                out.add(
                    Guess(
                        "Unix timestamp",
                        if (millis) "Milliseconds since 1970." else "Seconds since 1970.",
                        "ts", 80
                    )
                )
            }
        }

        if (v.length >= 8 && v.length % 4 == 0 && B64.matches(v) && !HEX.matches(v)) {
            out.add(Guess("Base64", "Length is a multiple of four over the base64 alphabet.", "b64d", 70))
        } else if (v.length >= 8 && B64URL.matches(v) && v.any { it == '-' || it == '_' }) {
            out.add(Guess("Base64url", "Uses - and _ in place of + and /.", "b64d", 60))
        }

        if (v.contains('%') && Regex("%[0-9a-fA-F]{2}").containsMatchIn(v)) {
            out.add(Guess("Percent-encoded text", "Contains %XX escapes.", "urld", 85))
        }

        if (out.isEmpty()) {
            out.add(
                Guess(
                    "Plain text",
                    "Nothing structural stands out. Try the decoders if you " +
                        "expect it to be encoded.",
                    confidence = 20
                )
            )
        }

        return out.sortedByDescending { it.confidence }
    }

    private const val B58 =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * Base58Check: true, false, or null when there is nothing to check.
     *
     * A legacy Bitcoin address is a version byte, twenty bytes of hash
     * and the first four bytes of the double SHA-256 of both. Getting
     * one character wrong changes the digest, which is the entire
     * point of the scheme and the reason an address can be read aloud.
     * Null for bech32, which carries a BCH checksum over a different
     * alphabet and is not decoded here.
     */
    internal fun base58Check(address: String): Boolean? {
        if (address.startsWith("bc1")) return null
        return runCatching {
            var num = java.math.BigInteger.ZERO
            val radix = java.math.BigInteger.valueOf(58)
            address.forEach { c ->
                val d = B58.indexOf(c)
                if (d < 0) return false
                num = num.multiply(radix).add(java.math.BigInteger.valueOf(d.toLong()))
            }
            // Leading '1's are leading zero bytes, which BigInteger
            // cannot carry.
            val leading = address.takeWhile { it == '1' }.length
            val body = num.toByteArray().dropWhile { it == 0.toByte() }
            val full = ByteArray(leading) + body.toByteArray()
            if (full.size != 25) return false
            val payload = full.copyOfRange(0, 21)
            val checksum = full.copyOfRange(21, 25)
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            val digest = sha.digest(sha.digest(payload))
            checksum.contentEquals(digest.copyOfRange(0, 4))
        }.getOrDefault(false)
    }
}
