package com.osint.unified.domain.executor

/**
 * The user agent the SEC requires, built from the investigator's own
 * contact address.
 *
 * EDGAR rejects generic agents with 403 and specifically wants a contact
 * *address* — a descriptive string carrying only a URL still gets refused.
 *
 * That address used to be the developer's, compiled into every APK and
 * committed to a public repository. It meant every EDGAR request anyone
 * ever made was signed by one person, that anyone handed the APK was using
 * that identity without knowing, and that the repository published a direct
 * pivot to its author. In an OSINT tool, of all things.
 *
 * It is a setting now, blank by default, and the EDGAR tool says so rather
 * than borrowing someone else's identity.
 */
internal object SecAgent {

    /**
     * Opens with "could not be reached" on purpose.
     *
     * This string is a tool's entire output, and the classifier reads
     * the first non-blank line: without those words a source that was
     * never consulted — graded A, because EDGAR is — appeared in the
     * dossier as one that answered and found nothing.
     */
    const val NOT_SET =
        "SEC EDGAR could not be reached: it requires a contact email " +
            "address with every request, and " +
            "refuses anonymous ones. Add yours in Settings → SEC contact " +
            "email to use this source. It is sent only to sec.gov."

    fun userAgent(contactEmail: String): String? {
        val email = contactEmail.trim()
        if (!email.contains("@") || email.length < 5) return null
        return "OSINT Unified research $email"
    }
}
