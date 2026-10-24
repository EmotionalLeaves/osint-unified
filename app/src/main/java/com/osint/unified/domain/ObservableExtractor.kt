package com.osint.unified.domain

/**
 * Scans free-form tool output for new observables (emails, IPs, domains) so the
 * user can pivot — tap a finding to launch a fresh investigation on it.
 */
object ObservableExtractor {

    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val ipv4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")
    private val domain = Regex("""\b(?:[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}\b""")

    /**
     * How much of a result is read looking for pivots.
     *
     * The domain pattern is quadratic in a run of dotted labels — a
     * string of "a." repeated six thousand times takes over a second
     * — and this is called during composition for every card on the
     * dossier, on raw output that can be a megabyte of JSON. The
     * pivots worth offering are in the first part of an answer; the
     * tail of a megabyte is a certificate dump.
     */
    private const val MAX_SCANNED = 96 * 1024

    /**
     * Suffixes that make a dotted word a file rather than a host.
     *
     * The pattern is "labels, a dot, two or more letters", which
     * "config.yaml", "index.html" and "Mr.Smith" all satisfy — and
     * .yaml and .next are even TLD-shaped, so a suffix allow-list
     * alone would not catch them. These were being offered to the
     * person as a new investigation to launch.
     */
    private val NOT_HOSTS = setOf(
        "yaml", "yml", "json", "xml", "html", "htm", "css", "js", "ts",
        "md", "txt", "log", "csv", "tsv", "png", "jpg", "jpeg", "gif",
        "svg", "webp", "pdf", "zip", "tar", "gz", "exe", "dll", "so",
        "py", "rb", "go", "rs", "java", "kt", "kts", "c", "h", "cpp",
        "sh", "bat", "ps1", "ini", "conf", "cfg", "toml", "lock",
        "jar", "apk", "db", "sql", "bak", "tmp", "old", "swp", "env"
    )

    /** Returns up to [limit] distinct observables found in [raw], minus [exclude]. */
    fun extract(raw: String, exclude: String, limit: Int = 10): List<String> {
        if (raw.isBlank()) return emptyList()
        val text = if (raw.length > MAX_SCANNED) raw.take(MAX_SCANNED) else raw
        val found = LinkedHashSet<String>()
        email.findAll(text).forEach { found.add(it.value) }
        ipv4.findAll(text).forEach { found.add(it.value) }
        domain.findAll(text).forEach { m ->
            val v = m.value.lowercase()
            if (v.substringAfterLast('.') !in NOT_HOSTS) found.add(v)
        }
        return found
            .filter { it != exclude && it.lowercase() != exclude.lowercase() && it.length in 4..120 }
            .take(limit)
    }
}
