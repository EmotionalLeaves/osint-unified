package com.osint.unified.domain

/**
 * Whether a picture says who made it, and whether it says a machine did.
 *
 * This is the strongest question left to ask about an image offline,
 * and the app could not ask it. EXIF says what camera took a
 * photograph; it says nothing about a picture that no camera took. The
 * standards that do — C2PA Content Credentials, and the generators'
 * own habits of writing their prompt into the file — are all in bytes
 * the app already has in its hand.
 *
 * What this does NOT do, and says so in its own output: verify a
 * signature. A C2PA manifest is signed, and checking that signature
 * needs a trust list, a certificate chain and a clock, none of which
 * this app has. So a manifest here is read as a CLAIM — "this file
 * says it was made by X" — which is exactly what an unverified
 * manifest is. Treating an unverified claim as proof is the one
 * mistake this feature could make that would be worse than not having
 * it.
 *
 * And the absence of all of this proves nothing at all. Every
 * generator's output loses its metadata the moment it is screenshotted,
 * re-encoded, or passed through a platform that strips it — which is
 * most of them. "No credentials found" is a fact about this copy of
 * the file.
 */
object ContentCredentials {

    data class Finding(
        /** What was found, in the file's own words where possible. */
        val what: String,
        /** Where in the file it came from. */
        val where: String
    )

    data class Read(
        val hasManifest: Boolean,
        val findings: List<Finding>,
        val saysGenerated: Boolean,
        val bytesScanned: Int
    )

    /**
     * Strings a C2PA manifest, an XMP packet or a generator's own text
     * chunk puts in the file, and what each one means.
     *
     * Matched against the file's printable runs rather than parsed:
     * CBOR inside JUMBF inside an APP11 segment is three nested binary
     * formats, and a full reader for them is a library rather than a
     * file. The strings these formats use are stable, long and
     * specific enough that finding them is not a guess — "c2pa.actions"
     * does not occur in a photograph by accident.
     */
    private val MARKERS: List<Triple<String, String, Boolean>> = listOf(
        // Marker, what it means, whether it means "a machine made this".
        Triple("trainedAlgorithmicMedia", "says it was created by a generative model", true),
        Triple("compositeWithTrainedAlgorithmicMedia", "says a generative model contributed part of it", true),
        Triple("algorithmicMedia", "says it was created by an algorithm", true),
        Triple("c2pa.created", "carries a C2PA claim about how it was created", false),
        Triple("c2pa.actions", "carries a C2PA record of what was done to it", false),
        Triple("c2pa.edited", "carries a C2PA record of an edit", false),
        Triple("c2pa.placed", "carries a C2PA record of content placed into it", false),
        Triple("c2pa.training-mining", "carries a C2PA statement about training and mining", false),
        Triple("urn:uuid:", "carries a C2PA manifest identifier", false),
        Triple("contentcredentials.org", "points at the Content Credentials verifier", false),
        Triple("digitalSourceType", "declares the kind of source it came from", false),
    )

    /**
     * Generators that write their own name or their prompt into the
     * file, which is far more common than a signed manifest.
     */
    private val GENERATORS: List<Pair<String, String>> = listOf(
        "Stable Diffusion" to "Stable Diffusion",
        "stable-diffusion" to "Stable Diffusion",
        "Midjourney" to "Midjourney",
        "DALL" to "DALL·E",
        "openai" to "OpenAI",
        "Firefly" to "Adobe Firefly",
        "Adobe Firefly" to "Adobe Firefly",
        "ComfyUI" to "ComfyUI",
        "Automatic1111" to "Automatic1111",
        "NovelAI" to "NovelAI",
        "Imagen" to "Google Imagen",
        "Gemini" to "Google Gemini",
        "Flux" to "Flux",
        "Leonardo.Ai" to "Leonardo.Ai",
        "Craiyon" to "Craiyon",
        "invokeai" to "InvokeAI",
    )

    /** PNG text chunks a generator fills with its own settings. */
    private val PROMPT_KEYS = listOf("parameters", "prompt", "workflow", "Comment", "Dream")

    /**
     * [bytes] is the start of the file; the interesting part of a JPEG
     * or a PNG is at the front, and reading all of a twenty-megabyte
     * image to look for a header is a cost for nothing.
     */
    fun read(bytes: ByteArray): Read {
        val text = printableRuns(bytes)
        val findings = ArrayList<Finding>()
        var generated = false

        val jumbf = bytes.indexOfAscii("jumb") >= 0 || bytes.indexOfAscii("c2pa") >= 0
        val caBX = bytes.indexOfAscii("caBX") >= 0

        // A generative assertion counts only inside a manifest.
        //
        // "algorithmicMedia" is a fragment of a real IPTC newscode and
        // also a fragment of two English words with binary between
        // them. On its own it is not evidence of anything, and
        // "THIS FILE SAYS IT WAS MACHINE-GENERATED" is the strongest
        // sentence this app prints about a picture — it does not get
        // to rest on a substring.
        val inManifest = jumbf || caBX || text.contains("digitalSourceType", true)
        MARKERS.forEach { (marker, meaning, meansGenerated) ->
            if (!text.contains(marker, ignoreCase = true)) return@forEach
            if (meansGenerated && !inManifest) return@forEach
            findings.add(Finding(meaning, "C2PA manifest"))
            if (meansGenerated) generated = true
        }
        GENERATORS.forEach { (needle, name) ->
            if (text.contains(needle, ignoreCase = true)) {
                findings.add(Finding("names $name", "metadata written by the tool"))
            }
        }
        PROMPT_KEYS.forEach { key ->
            // A PNG text chunk is the keyword, a NUL, then the value,
            // so the keyword appears on its own in the printable runs.
            // The chunk type sits immediately before the keyword in a
            // PNG — "tEXtparameters\u0000" — so a plain word boundary
            // never matches and the first version of this found
            // nothing at all.
            val at = Regex(
                "(?:^|[^A-Za-z0-9]|tEXt|iTXt|zTXt)" + Regex.escape(key) + "\\u0000",
                RegexOption.IGNORE_CASE
            )
            if (at.containsMatchIn(text)) {
                findings.add(
                    Finding("carries a \"$key\" text chunk, which generators use " +
                        "for the prompt and the settings", "PNG text chunk")
                )
            }
        }
        if (caBX) findings.add(Finding("carries a PNG C2PA chunk", "caBX chunk"))

        return Read(
            hasManifest = jumbf || caBX,
            findings = findings.distinctBy { it.what },
            saysGenerated = generated,
            bytesScanned = bytes.size
        )
    }

    /** What to print in a dossier. */
    fun describe(read: Read): String = buildString {
        append("Content Credentials & provenance\n")
        if (read.findings.isEmpty() && !read.hasManifest) {
            append("Nothing found in the first ")
            append(read.bytesScanned / 1024)
            append(" kB of this file: no C2PA manifest, no generator ")
            append("metadata, no prompt chunk.\n\n")
            append("That is a fact about this copy of the file and not about ")
            append("the picture. A screenshot, a re-encode, or any platform ")
            append("that strips metadata — which is most of them — removes ")
            append("all of it, and an image made by a model and then ")
            append("screenshotted looks exactly like this.")
            return@buildString
        }
        if (read.saysGenerated) {
            append("THIS FILE SAYS IT WAS MACHINE-GENERATED.\n")
        } else if (read.hasManifest) {
            append("This file carries Content Credentials.\n")
        }
        read.findings.forEach { append("• ${it.what} (${it.where})\n") }
        append("\nThis is what the file CLAIMS. The signature on a C2PA ")
        append("manifest is not checked here — doing that needs a trust ")
        append("list and a certificate chain this app does not carry — so ")
        append("treat it as the file's own statement about itself, which ")
        append("anyone who can write the file can write. Verify at ")
        append("https://contentcredentials.org/verify if it matters.\n")
        if (!read.saysGenerated) {
            append("\nNothing here says a model made it. Nothing here says ")
            append("one did not, either: a manifest records what the last ")
            append("tool to touch the file chose to record.")
        }
    }

    /**
     * The printable ASCII runs in a block of bytes, joined.
     *
     * Binary between them becomes a separator, so a marker cannot be
     * formed by accident out of two unrelated fragments.
     */
    private fun printableRuns(bytes: ByteArray, minRun: Int = 3): String {
        val sb = StringBuilder(bytes.size / 4)
        var run = StringBuilder()
        bytes.forEach { b ->
            val c = b.toInt() and 0xFF
            // NUL is kept, because a PNG text chunk's keyword is
            // terminated by one and that is how the keyword is
            // recognised as a keyword rather than as a word in a
            // caption.
            if (c == 0) {
                if (run.length >= minRun) sb.append(run).append('\u0000')
                run = StringBuilder()
            } else if (c in 32..126) {
                run.append(c.toChar())
            } else {
                if (run.length >= minRun) sb.append(run).append('\n')
                run = StringBuilder()
            }
        }
        if (run.length >= minRun) sb.append(run)
        return sb.toString()
    }

    private fun ByteArray.indexOfAscii(needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || n.size > size) return -1
        outer@ for (i in 0..size - n.size) {
            for (j in n.indices) if (this[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}
