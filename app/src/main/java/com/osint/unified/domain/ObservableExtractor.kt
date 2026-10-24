package com.osint.unified.domain

/**
 * Scans free-form tool output for new observables (emails, IPs, domains) so the
 * user can pivot — tap a finding to launch a fresh investigation on it.
 */
object ObservableExtractor {

    // The host and address shapes live in Hostnames, which explains
    // at length why they are not written here as the obvious regex.
    // Four kilobytes of dotted labels in a stored result used to
    // crash this app on the composition thread, every time the case
    // was opened, for ever.
    private val ipv4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")

    /**
     * How much of a result is read looking for pivots.
     *
     * This runs during composition for every card on the dossier,
     * on raw output that can be a megabyte of JSON, and the pivots
     * worth offering are in the first part of an answer — the tail
     * of a megabyte is a certificate dump.
     *
     * It was written as the mitigation for a quadratic pattern and
     * was not one: the pattern crashed with a StackOverflowError at
     * four kilobytes, a twenty-fourth of this. The patterns are
     * linear now (see [Hostnames]) and this is back to being what it
     * says it is — a bound on work, not a safety belt.
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
    internal val NOT_HOSTS = setOf(
        "yaml", "yml", "json", "xml", "html", "htm", "css", "js", "ts",
        "md", "txt", "log", "csv", "tsv", "png", "jpg", "jpeg", "gif",
        "svg", "webp", "pdf", "zip", "tar", "gz", "exe", "dll", "so",
        "py", "rb", "go", "rs", "java", "kt", "kts", "cpp",
        "sh", "bat", "ps1", "ini", "conf", "cfg", "toml", "lock",
        "jar", "apk", "db", "sql", "bak", "tmp", "old", "swp", "env",
        // The comment above names ".next" as the example and it was
        // not in the list. "c" and "h" were, and could never match:
        // the shape requires two letters at the end.
        "next", "nuxt", "cache", "map", "min", "iml", "gradle",
        "properties", "yarn", "npmrc", "dockerignore", "gitignore"
    )

    /** Returns up to [limit] distinct observables found in [raw], minus [exclude]. */
    fun extract(raw: String, exclude: String, limit: Int = 10): List<String> {
        if (raw.isBlank()) return emptyList()
        val text = if (raw.length > MAX_SCANNED) raw.take(MAX_SCANNED) else raw
        val found = LinkedHashSet<String>()
        Hostnames.emailsIn(text).forEach { found.add(it) }
        ipv4.findAll(text).forEach { found.add(it.value) }
        Hostnames.hostsIn(text).forEach { host ->
            val v = host.lowercase()
            if (v.substringAfterLast('.') !in NOT_HOSTS) found.add(v)
        }
        return found
            .filter { it != exclude && it.lowercase() != exclude.lowercase() && it.length in 4..120 }
            .take(limit)
    }
}
