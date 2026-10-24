package com.osint.unified.domain

/**
 * Host and address shapes that cannot be turned into a crash.
 *
 * Five files in this package wrote the same pattern for a hostname:
 *
 *     \b(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}\b
 *
 * It is the textbook shape and it is a loaded gun. `java.util.regex`
 * — which is what Kotlin's `Regex` compiles to — implements the
 * repetition of a *group* recursively, one stack frame per
 * iteration. Measured on this project's own toolchain, against a
 * string of "a." repeated:
 *
 *     1,500 labels (3,000 chars)  ->  4 ms
 *     2,000 labels (4,000 chars)  ->  StackOverflowError
 *
 * `ObservableExtractor.extract` is called from `ResultCard` inside a
 * `remember` block, which runs during composition on the main
 * thread, over `rawOutput` straight out of the database. A
 * StackOverflowError is an `Error`; Compose does not catch it. So
 * four kilobytes of dotted labels in any stored result — a
 * certificate SAN dump, a zone transfer, a page title the subject
 * controls — crashes the app every single time that case is opened,
 * for ever. The cap that was supposed to be the mitigation is
 * 96 KB, twenty-four times above the threshold, and it was written
 * against a linear worry.
 *
 * The companion defect is the email pattern, which does not
 * overflow but is quadratic: 3.4 seconds over 24 KB, and
 * fifty-odd seconds at the cap.
 *
 * So: no repeated groups here. One bounded character-class run,
 * which `java.util.regex` compiles to an iterative matcher, and the
 * structure a hostname actually has — label lengths, hyphens,
 * doubled dots, the TLD — is checked in Kotlin afterwards, where it
 * costs one pass and is easier to read than the regex ever was.
 */
internal object Hostnames {

    /**
     * Something host-shaped, loosely, for scanning free text.
     *
     * Deliberately permissive: [isHostname] does the deciding. The
     * only job of the pattern is to find candidates in linear time.
     */
    val SHAPE = Regex("""\b[A-Za-z0-9][A-Za-z0-9.\-]{0,252}[A-Za-z]\b""")

    private fun Char.isLocalPart(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '.' || this == '_' || this == '%' || this == '+' || this == '-'

    private fun Char.isHostPart(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '.' || this == '-'

    /**
     * Every address in [text], found by looking for the `@` first.
     *
     * Not a regex, and that is the point. The pattern this replaces
     * began with an unbounded run of local-part characters, so at
     * every one of ninety-six thousand starting positions the engine
     * consumed the rest of the text and then failed on the `@` —
     * sixty-one seconds over the scan cap, measured. Making the run
     * possessive stops the backtracking within one attempt and does
     * nothing about the attempts, because the cost is in restarting.
     *
     * An address has exactly one `@`, and there are few of them in
     * any text, so finding those first and growing outwards from
     * each is linear and finishes in microseconds. The local part is
     * bounded at 64 characters and the host at 253, which are the
     * real limits, so neither walk can run away.
     */
    fun emailsIn(text: String): List<String> {
        val out = ArrayList<String>()
        var at = text.indexOf('@')
        while (at >= 0) {
            var start = at
            while (start > 0 && text[start - 1].isLocalPart() && at - start < 64) start--
            var end = at + 1
            while (end < text.length && text[end].isHostPart() && end - at <= 253) end++
            if (start < at && end > at + 1) {
                emailIn(text.substring(start, end))?.let { out.add(it) }
            }
            at = text.indexOf('@', maxOf(end, at + 1))
        }
        return out
    }

    /** Every hostname in [text], as a linear scan. */
    fun hostsIn(text: String): List<String> =
        SHAPE.findAll(text).mapNotNull { hostIn(it.value) }.toList()

    /**
     * Whether [v] is really a hostname: at least two labels, each
     * 1–63 characters, none starting or ending in a hyphen, no empty
     * label, and a top-level label of two or more letters.
     *
     * The old pattern enforced most of this and got the cost wrong.
     * This enforces all of it and costs one pass.
     */
    fun isHostname(v: String): Boolean {
        if (v.length !in 4..253) return false
        if (v.startsWith('.') || v.endsWith('.')) return false
        val labels = v.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith('-') || label.endsWith('-')) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it in 'A'..'Z' || it in 'a'..'z' }
    }

    /**
     * The hostname inside a match of [SHAPE], or null.
     *
     * A permissive scan can end on a dot or a hyphen — "see
     * example.com." at the end of a sentence — so the candidate is
     * trimmed before it is judged.
     */
    fun hostIn(candidate: String): String? {
        val v = candidate.trim('.', '-')
        return if (isHostname(v)) v else null
    }

    /** Whether [v] is an address: a local part, an `@`, and a host. */
    fun isEmail(v: String): Boolean {
        val at = v.lastIndexOf('@')
        if (at <= 0 || at == v.length - 1) return false
        val local = v.substring(0, at)
        if (local.length > 64) return false
        if (local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        return isHostname(v.substring(at + 1))
    }

    /** The address inside a match of [EMAIL_SHAPE], or null. */
    fun emailIn(candidate: String): String? {
        val v = candidate.trimEnd('.', '-')
        return if (isEmail(v)) v else null
    }
}
