package com.osint.unified.domain

/**
 * Scans free-form tool output for new observables (emails, IPs, domains) so the
 * user can pivot — tap a finding to launch a fresh investigation on it.
 */
object ObservableExtractor {

    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val ipv4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")
    private val domain = Regex("""\b(?:[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}\b""")

    /** Returns up to [limit] distinct observables found in [raw], minus [exclude]. */
    fun extract(raw: String, exclude: String, limit: Int = 10): List<String> {
        if (raw.isBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        email.findAll(raw).forEach { found.add(it.value) }
        ipv4.findAll(raw).forEach { found.add(it.value) }
        domain.findAll(raw).forEach { found.add(it.value.lowercase()) }
        return found
            .filter { it != exclude && it.lowercase() != exclude.lowercase() && it.length in 4..120 }
            .take(limit)
    }
}
