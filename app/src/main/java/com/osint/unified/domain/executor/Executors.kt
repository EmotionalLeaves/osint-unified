package com.osint.unified.domain.executor

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.osint.unified.data.AppSettings
import com.osint.unified.data.Overpass
import com.osint.unified.domain.orStop
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Findings
import com.osint.unified.domain.Tool
import com.osint.unified.network.Http
import com.osint.unified.network.Http.bodyPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * Executes a single tool against an input and returns its raw output text.
 * Raw output is intentionally preserved verbatim so nothing is lost before
 * parsing or export.
 */
interface ToolExecutor {
    suspend fun execute(tool: Tool, input: String): Result<String>

    /**
     * What the tool meant by what it returned, when it can say.
     *
     * Null means "read the prose", which is what every tool used to force.
     * A tool that answers here is stating its verdict instead of leaving it
     * to a substring match over English, and cannot be flipped by someone
     * else's page title landing on its first line.
     */
    fun classify(tool: Tool, output: String): Findings.Kind? = null
}

/**
 * Runs CLOUD tools via the deployed backend: POST {baseUrl}/{toolId} with a
 * JSON body {"input": "..."} and returns the response body text.
 */
class CloudToolExecutor(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.client
) : ToolExecutor {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun execute(tool: Tool, input: String): Result<String> =
        withContext(Dispatchers.IO) {
            val base = settings.backendBaseUrl.trimEnd('/')
            if (base.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "No backend URL set. Open settings (gear icon) and enter your deployed backend URL."
                    )
                )
            }
            try {
                val payload = buildJsonObject { put("input", input) }.toString()
                val token = settings.backendToken
                if (token.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "No backend token set. Your backend refuses unauthenticated " +
                                "requests, which is what stops anyone who finds its URL " +
                                "running scans from your account. Add the token in settings."
                        )
                    )
                }
                val request = Request.Builder()
                    .url("$base/${tool.id}")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(text)
                    else Result.failure(IOException("HTTP ${resp.code}: ${text.take(300)}"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // CancellationException extends IllegalStateException, so
                // `catch (e: Exception)` swallowed it and handed back a
                // failure — and the layer above, which marks a stopped
                // tool "cancelled", never saw one.
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

/**
 * Runs LOCAL tools shipped as native libraries. On Android 10+ you cannot exec
 * a binary from app-writable storage (W^X), but files in the app's
 * nativeLibraryDir ARE executable. The convention: ship tool <id> as
 * "lib<id>.so" in jniLibs/arm64-v8a. Until a binary is bundled, this returns a
 * clear, honest error rather than pretending it ran.
 */
class LocalToolExecutor(
    private val nativeLibraryDir: String,
    private val workDir: String
) : ToolExecutor {

    override suspend fun execute(tool: Tool, input: String): Result<String> =
        withContext(Dispatchers.IO) { runTool(tool, input) }

    private suspend fun runTool(tool: Tool, input: String): Result<String> =
        coroutineScope {
            val binary = File(nativeLibraryDir, "lib${tool.id}.so")
            if (!binary.exists()) {
                return@coroutineScope Result.failure(
                    IllegalStateException(
                        "${tool.name} is a local tool but its binary isn't bundled in this build. " +
                            "It ships as jniLibs/arm64-v8a/lib${tool.id}.so once added to the CI build step."
                    )
                )
            }
            // An argument that begins with a dash is read as a flag by every
            // one of these tools, so a target called "-oJ" could rewrite the
            // command line.
            if (input.trimStart().startsWith("-")) {
                return@coroutineScope Result.failure(
                    IllegalArgumentException("Refusing to pass an argument beginning with '-'.")
                )
            }

            // Each run gets its own directory. A shared target file was being
            // overwritten by whichever workflow got there second.
            val runDir = File(workDir, "run-${System.nanoTime()}").apply { mkdirs() }
            try {
                val command = buildCommand(tool, input, binary.absolutePath, runDir)
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .apply {
                        directory(runDir)
                        // Tools expect a writable HOME/TMP for config + caches.
                        environment()["HOME"] = runDir.absolutePath
                        environment()["TMPDIR"] = runDir.absolutePath
                    }
                    .start()

                // readText() blocks until the child closes stdout, so the
                // timeout below was unreachable: a hung tool never got as far
                // as waitFor and destroyForcibly never ran. Worse, the read is
                // not interruptible, so cancelling the coroutine left the
                // process running — burning CPU and mobile data until it
                // finished on its own. Reading on a job that can be killed,
                // with a cap on how much it may produce, fixes both.
                val output = StringBuilder()
                // The search moving on must stop the process, not orphan it:
                // an orphan keeps contacting the target after the person
                // stopped asking.
                val guard = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
                val reader = launch(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (output.length < MAX_OUTPUT_CHARS) {
                                output.append(buf, 0, n)
                            }
                        }
                    }
                }
                val finished = withContext(Dispatchers.IO) {
                    process.waitFor(tool.timeoutMs, TimeUnit.MILLISECONDS)
                }
                if (!finished) {
                    process.destroyForcibly()
                    reader.cancel()
                    guard.cancel()
                    return@coroutineScope Result.failure(
                        IOException("${tool.name} timed out after ${tool.timeoutMs} ms")
                    )
                }
                reader.join()
                guard.cancel()
                val text = output.toString()
                Result.success(
                    if (text.length >= MAX_OUTPUT_CHARS) {
                        text + "\n[truncated at ${MAX_OUTPUT_CHARS / 1024} KB]"
                    } else {
                        text
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                runCatching { runDir.deleteRecursively() }
            }
        }

    /**
     * Builds the command line. When a workflow passes a multi-line host list
     * (e.g. subfinder output into httpx), it's written to a file and fed via
     * `-l` instead of `-u`.
     */
    private fun buildCommand(
        tool: Tool,
        input: String,
        binPath: String,
        runDir: File
    ): List<String> {
        val cmd = mutableListOf(binPath)
        if (tool.id == "httpx" && input.contains('\n')) {
            val listFile = File(runDir, "targets.txt").apply { writeText(input) }
            cmd += listOf("-l", listFile.absolutePath, "-json", "-silent", "-title", "-tech-detect", "-status-code")
        } else {
            cmd += argsFor(tool, input)
        }
        return cmd
    }

    private fun argsFor(tool: Tool, input: String): List<String> = when (tool.id) {
        "sherlock" -> listOf(input, "--print-found", "--no-color")
        "maigret" -> listOf(input, "--json", "simple")
        "holehe" -> listOf(input, "--only-used")
        "phoneinfoga" -> listOf("scan", "-n", input)
        "subfinder" -> listOf("-d", input, "-silent", "-json")
        "httpx" -> listOf("-u", input, "-json", "-silent", "-title", "-tech-detect", "-status-code")
        "exiftool" -> listOf("-j", "-a", "-u", "-g1", input)
        // "--" ends option parsing, so a positional target can never be read
        // as a flag no matter what it contains.
        else -> listOf("--", input)
    }

    private companion object {
        /** A runaway tool could otherwise fill memory with its own output. */
        const val MAX_OUTPUT_CHARS = 2_000_000
    }
}

/**
 * Runs DIRECT tools: public APIs the app calls itself, with no backend. Today
 * that is Overpass (OpenStreetMap infrastructure near a coordinate).
 */
class DirectToolExecutor(
    private val appContext: android.content.Context,
    private val usernameChecker: UsernameChecker,
    private val exifReader: com.osint.unified.data.ExifReader,
    private val settings: AppSettings,
    private val apis: OsintApis = OsintApis(),
    private val deep: DeepApis = DeepApis({ settings.secContactEmail }),
    private val people: PeopleApis = PeopleApis(),
    private val handles: HandleApis = HandleApis(),
    /** Held, not rebuilt: it owns a six-hour inventory cache. */
    private val cctv: com.osint.unified.data.CctvDataSource =
        com.osint.unified.data.CctvDataSource(),
    private val client: OkHttpClient = Http.client,
    /** Last, and defaulted: a parameter added in the middle of this
     *  list silently re-binds every positional argument its one
     *  caller passes, which is how the first attempt at this put it
     *  on the wrong executor. */
    private val imageText: com.osint.unified.data.ImageText =
        com.osint.unified.data.ImageText(appContext)
) : ToolExecutor {

    /** One endpoint directoryRun tried, and what came of it. */
    private data class Ran(
        val name: String,
        val url: String,
        val body: String?,
        /** Non-null when it was never asked, or asked and refused. */
        val why: String?
    )

    /**
     * Whether a response actually said anything.
     *
     * The test was "more than twenty bytes", which calls
     * {"data":{"items":[]}} a result because it is twenty-one
     * characters long, and calls a fifteen-character real answer
     * nothing. Structural punctuation is not content, so it is
     * stripped before the length is counted.
     */
    private fun hasContent(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val meat = body.filterNot { it.isWhitespace() || it in "{}[]\",:" }
        if (meat.length < 8) return false
        // An envelope whose only words are its own field names.
        return !EMPTY_ENVELOPE.matches(body.trim())
    }

    /** The line directoryRun writes under its own header. */
    /** {"results":[]}, {"data":{"items":[]}}, and the rest of the
     *  shapes that mean "nothing" in more than twenty bytes. */
    private val EMPTY_ENVELOPE =
        Regex("""^[\[{]\s*(?:"[\w.]+"\s*:\s*(?:\[\s*\]|\{\s*\}|null|0|false)\s*,?\s*)*[\]}]$""")

    private val DIRECTORY_TALLY =
        Regex("""^(\d+) of (\d+) executable endpoints returned data""", RegexOption.MULTILINE)

    private val formUrlEncoded = "application/x-www-form-urlencoded".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The verdict, for the tools where getting it wrong matters most.
     *
     * Each of these matches the exact opening its own helper writes — an
     * anchored prefix, not a substring hunted for anywhere in the text — so
     * a value from a third party appearing further along the line cannot
     * change the answer. Sanctions screening is first because a false
     * "clean" there is the most consequential thing this app can say.
     */
    override fun classify(tool: Tool, output: String): Findings.Kind? {
        val head = output.trimStart().lineSequence()
            .firstOrNull { it.isNotBlank() }.orEmpty()
        fun starts(vararg prefixes: String) = prefixes.any { head.startsWith(it) }

        return when (tool.id) {
            "sanctions" -> when {
                starts("The OFAC list could not be read",
                    "The OFAC check could not be completed") -> Findings.Kind.UNREACHABLE
                starts("OFAC SDN: no sanctioned entity") -> Findings.Kind.ABSENT
                // Neither of these is an absence. One is a question
                // the tool could not act on; the other is a name the
                // romanised list cannot represent, which says
                // nothing either way about whether the person is on
                // it. Both were falling through to the prose reader.
                starts("Give a name to search", "Give at least three",
                    "The bundled OFAC list is romanised") -> Findings.Kind.UNREACHABLE
                // The cap filled on the primary list, so the alias
                // list was never opened: too many matches to be a
                // match, and an untested half.
                starts("OFAC SDN — more than") -> Findings.Kind.UNREACHABLE
                starts("OFAC Specially Designated") -> Findings.Kind.HIT
                else -> null
            }
            "unsanctions" -> when {
                starts("The UN consolidated list is not bundled") -> Findings.Kind.UNREACHABLE
                starts("UN consolidated list: no") -> Findings.Kind.ABSENT
                starts("Give at least three") -> Findings.Kind.UNREACHABLE
                // There was no HIT case at all, so a name found on
                // the UN consolidated list fell through to the prose
                // reader — the one place in the app where guessing
                // from English is least acceptable.
                starts("UN Security Council consolidated sanctions") -> Findings.Kind.HIT
                else -> null
            }
            "breachcat" -> when {
                starts("The breach catalogue is not bundled",
                    "The bundled breach catalogue could not be read") -> Findings.Kind.UNREACHABLE
                starts("Breach catalogue: no record") -> Findings.Kind.ABSENT
                starts("Known breaches involving") -> Findings.Kind.HIT
                starts("Give at least three") -> Findings.Kind.UNREACHABLE
                else -> null
            }
            // Every sweep, including the email one, states its verdict
            // on its first line as a field it computed from its own
            // counts. Matching English prose is what produced both of the
            // previous bugs here — first "no accounts" read as a hit, then
            // a full-coverage absence read as unreachable — so the prose
            // is no longer consulted.
            "sherlock", "maigret", "blackbird", "socialsweep", "emailaccounts" -> {
                val field = output.lineSequence()
                    .firstOrNull { it.startsWith(UsernameChecker.VERDICT_FIELD) }
                    ?.removePrefix(UsernameChecker.VERDICT_FIELD)?.trim()
                when (field) {
                    UsernameChecker.VERDICT_HIT -> Findings.Kind.HIT
                    UsernameChecker.VERDICT_ABSENT -> Findings.Kind.ABSENT
                    // Partial coverage is neither: some of the list was
                    // asked and said no, and the rest was never asked.
                    UsernameChecker.VERDICT_PARTIAL -> Findings.Kind.UNREACHABLE
                    UsernameChecker.VERDICT_UNREACHABLE -> Findings.Kind.UNREACHABLE
                    else -> if (starts("Could not load any site ruleset"))
                        Findings.Kind.UNREACHABLE else null
                }
            }
            "framework", "dorks", "websearch", "reverseimage" ->
                // These are lists of places to look, not findings about the
                // target. Counting them as hits inflated every dossier.
                Findings.Kind.ABSENT
            // toolrun was in that list and does not belong in it.
            //
            // It is the one tool that takes the curated directory and
            // actually runs every endpoint in it, in parallel, and
            // summarises the bodies. Grading it ABSENT whatever it
            // returned meant "5 of 7 executable endpoints returned
            // data" was filed under NOTHING FOUND and collapsed —
            // the single piece of evidence that this app is not a
            // bookmark bar, hidden behind a line saying nothing was
            // found. It is also marked touchesTarget, so it is in no
            // automatic set and the only way to see it at all is to
            // run it by hand.
            "toolrun" -> {
                // Its first line names the target, so the verdict is
                // the line after it, which it writes itself.
                val tally = DIRECTORY_TALLY.find(output)
                val ran = tally?.groupValues?.get(1)?.toIntOrNull()
                val outOf = tally?.groupValues?.get(2)?.toIntOrNull()
                when {
                    tally == null -> null
                    // "0 of 0" is not a search that found nothing.
                    //
                    // Three observable types — image, phone and an
                    // unrecognised one — have no fetchable templates
                    // in the directory at all, so the tally is
                    // always zero of zero while the rest of the
                    // output is a page of runnable links. Grading
                    // that ABSENT buries it under NOTHING FOUND,
                    // which is the thing this case was added to
                    // stop, for three types out of eleven.
                    outOf == 0 -> null
                    ran == 0 -> Findings.Kind.ABSENT
                    else -> Findings.Kind.HIT
                }
            }
            // A tool that says it cannot take this input has not
            // looked. Three of them answer an IPv6 search with a
            // careful sentence explaining that they need an IPv4
            // address, and none of those sentences matches an
            // absence or an unreachable shape — so they were filed
            // as findings and counted in "returned intel", three
            // cards whose content is that they did not run.
            // The Overpass-backed place tools, graded from the
            // sentences they write rather than from prose matching.
            //
            // Two of these were the oldest open report in the file:
            // "infrastructure scan, overpass and military sites have
            // never worked". The layers were fixed and the tools were
            // not, and on top of that an empty answer was read as a
            // finding, because "No military-tagged sites within 25 km"
            // begins with "No" but is not any of the phrases the
            // absence list holds, and "0 mapped features" is not the
            // word no at all.
            "milsites", "osmrecon", "overpass_turbo" -> when {
                starts("Overpass could not be reached") -> Findings.Kind.UNREACHABLE
                starts(
                    "No military-tagged sites",
                    "No mapped infrastructure",
                    "No mapped places or structures"
                ) -> Findings.Kind.ABSENT
                head.contains("within") || head.contains("Infrastructure within") ->
                    Findings.Kind.HIT
                else -> null
            }
            // This one does not state its verdict on the first line.
            //
            // When there is no camera feed nearby, cctvNear says so
            // and then says what OpenStreetMap holds — so a result
            // listing seven mapped surveillance cameras, which is
            // real intel about the place, opens with "No public
            // traffic-camera feed within 50 km" and was filed under
            // NOTHING FOUND and collapsed. The sentence about
            // OpenStreetMap not answering is on the second line too,
            // so the unreachable prefix could never match at all.
            // Read the whole output for this tool.
            "cctvnear" -> when {
                starts("Public camera inventories could not be reached") ->
                    Findings.Kind.UNREACHABLE
                output.contains("OpenStreetMap could not be asked") ->
                    Findings.Kind.UNREACHABLE
                output.contains("OpenStreetMap has no surveillance cameras") ->
                    Findings.Kind.ABSENT
                output.contains("surveillance camera") -> Findings.Kind.HIT
                head.contains("Public cameras within") -> Findings.Kind.HIT
                starts("No public traffic-camera feed") -> Findings.Kind.ABSENT
                else -> null
            }
            // Each of these opens with the account either existing
            // or not, in its own words.
            "chess", "lichess", "codeforces", "codeberg", "huggingface",
            "wikiuser" -> when {
                head.startsWith("No ") -> Findings.Kind.ABSENT
                head.contains("could not be asked") ||
                    head.contains("could not be reached") -> Findings.Kind.UNREACHABLE
                head.contains(" exists") -> Findings.Kind.HIT
                else -> null
            }
            "imagetext" -> when {
                starts("Give the image") -> Findings.Kind.UNREACHABLE
                starts("No readable text in this image") -> Findings.Kind.ABSENT
                head.contains("lines of text read from this image") -> Findings.Kind.HIT
                else -> null
            }
            "imagehead" -> when {
                starts("The host answered HTTP", "Give the image") ->
                    Findings.Kind.UNREACHABLE
                head.contains("The host serves this file as") -> Findings.Kind.HIT
                else -> null
            }
            "imagehistory" -> when {
                starts("The Wayback Machine has never captured") -> Findings.Kind.ABSENT
                starts("Give the image") -> Findings.Kind.UNREACHABLE
                head.contains("has been online since at least") -> Findings.Kind.HIT
                else -> null
            }
            "disposable" -> when {
                starts("Give an email address") -> Findings.Kind.UNREACHABLE
                starts("The disposable-domain list is not bundled",
                    "The bundled disposable-domain list could not be read") ->
                    Findings.Kind.UNREACHABLE
                head.contains("is a known disposable-mail domain") -> Findings.Kind.HIT
                head.contains("is not on the bundled list") -> Findings.Kind.ABSENT
                else -> null
            }
            "libravatar" -> when {
                starts("Libravatar has an avatar") -> Findings.Kind.HIT
                starts("No Libravatar avatar") -> Findings.Kind.ABSENT
                else -> Findings.Kind.UNREACHABLE
            }
            "ubuntukeys" -> when {
                starts("Give an email address") -> Findings.Kind.UNREACHABLE
                starts("No PGP key on the Ubuntu keyserver") -> Findings.Kind.ABSENT
                starts("The Ubuntu keyserver could not be reached") ->
                    Findings.Kind.UNREACHABLE
                head.contains("on the Ubuntu keyserver mentioning") -> Findings.Kind.HIT
                else -> null
            }
            // Half an answer is not an answer.
            //
            // Two certificate indexes are asked and either can
            // fail. With one failing and the other returning
            // nothing, the old output said "no certificates found"
            // — a statement about the domain from a source that was
            // never read — and the prose reader filed it as an
            // absence to match.
            // Read from its own first line, which it writes.
            //
            // This tool has four distinct outcomes — rate limited,
            // unreachable, unreadable, and an answer — and all four
            // were left to the prose reader. "No news coverage for
            // X in the last three days" matches an absence shape by
            // luck; the other three do not, so being rate limited
            // was filed as a finding.
            "gdelt" -> when {
                starts(
                    "GDELT is rate limiting",
                    "GDELT could not be reached",
                    "GDELT could not be read"
                ) -> Findings.Kind.UNREACHABLE
                starts("No news coverage for") -> Findings.Kind.ABSENT
                starts("News coverage for") -> Findings.Kind.HIT
                else -> null
            }
            "crtsh" -> when {
                starts("Certificate transparency unreachable") -> Findings.Kind.UNREACHABLE
                head.contains("so this is half an") -> Findings.Kind.UNREACHABLE
                starts("Certificate transparency: no certificates") -> Findings.Kind.ABSENT
                starts("Certificate transparency —") -> Findings.Kind.HIT
                else -> null
            }
            "phoneinfoga" -> when {
                head.contains("is a valid") -> Findings.Kind.HIT
                // Not an absence: the numbering plan has no opinion
                // about a string that is not a number, which is the
                // same shape as the three IPv4 refusals below.
                head.contains("is not a valid number") -> Findings.Kind.UNREACHABLE
                else -> null
            }
            "ptr", "torexit", "iprep" -> when {
                starts(
                    "A reverse lookup needs one IPv4 address",
                    "The Tor bulk exit list is IPv4 only",
                    "IP reputation needs an IPv4 address"
                ) -> Findings.Kind.UNREACHABLE
                else -> null
            }
            else -> null
        }
    }

    override suspend fun execute(tool: Tool, input: String): Result<String> =
        withContext(Dispatchers.IO) {
            when (tool.id) {
                "overpass_turbo" -> overpass(input)
                "milsites" -> militarySites(input)
                "osmrecon" -> osmRecon(input)
                "sherlock", "maigret", "blackbird" ->
                    runCatching { usernameChecker.check(input) }.orStop()
                "emailaccounts" -> runCatching {
                    // The sweep is a username sweep; the email only
                    // supplies a guess at the username. Everything it finds
                    // is a name collision until something else ties it to
                    // this address, and saying so in the output is the
                    // difference between a lead and a false identification.
                    val local = input.substringBefore('@').substringBefore('+').trim()
                    val caveat = "Searched as the username \"$local\", taken from " +
                        "before the @. Nothing below is tied to this email address: " +
                        "a site listed here has an account with that name, held by " +
                        "someone. Treat every line as a lead to corroborate, not a " +
                        "finding.\n\n"
                    caveat + usernameChecker.check(local)
                }.orStop()
                "phoneinfoga" -> phoneInfo(input)
                "webcrawl" -> apis.webcrawl(input)
                "archive" -> apis.archive(input)
                "crtsh" -> apis.crtsh(input)
                "dns" -> apis.dns(input)
                "ipinfo" -> apis.ipinfo(input)
                "gravatar" -> apis.gravatar(input)
                "wayback" -> apis.wayback(input)
                "revgeo" -> apis.revgeo(input)
                "shodan" -> apis.shodan(input)
                "dorks" -> apis.dorks(input)
                "framework" -> framework(input)
                "rdap" -> apis.rdap(input)
                "mailsec" -> apis.mailSecurity(input)
                "tlscert" -> apis.tlsCert(input)
                "sitefiles" -> apis.siteFiles(input)
                "torexit" -> apis.torExit(input)
                "headers" -> apis.securityHeaders(input)
                "ptr" -> apis.reverseDns(input)
                "bgpview" -> apis.bgpview(input)
                "hudsonrock" -> apis.hudsonRock(input)
                "urlscan" -> apis.urlscan(input)
                "websearch" -> apis.websearch(input)
                "github" -> apis.githubUser(input)
                "ghactivity" -> deep.githubActivity(input)
                "socialsweep" -> runCatching { deep.socialSweep(input) }.orStop()
                "toolrun" -> directoryRun(input)
                "emailperm" -> deep.emailPermutations(input)
                "typosquat" -> runCatching { deep.typosquat(input) }.orStop()
                "osmuser" -> deep.osmUser(input)
                "asnintel" -> deep.asnIntel(input)
                "iprep" -> deep.ipReputation(input)
                "otx" -> deep.threatExchange(input)
                "torrelay" -> deep.torRelay(input)
                "outages" -> deep.outages(input)
                "keybase" -> deep.keybase(input)
                "reddit" -> deep.reddit(input)
                "hackernews" -> deep.hackerNews(input)
                "telegram" -> deep.telegram(input)
                "wikidata" -> deep.wikidata(input)
                "breaches" -> deep.breaches(input)
                "emailgithub" -> deep.emailToGithub(input)
                "subsweep" -> deep.subdomainSweep(input)
                "hostintel" -> deep.hostIntel(input)
                "gleif" -> deep.legalEntity(input)
                "edgar" -> deep.secEdgar(input)
                "fbi" -> deep.fbiWanted(input)
                "aircraftreg" -> deep.aircraft(input)
                "wallet" -> deep.wallet(input)
                "sunterrain" -> deep.sunAndTerrain(input)
                "histweather" -> deep.historicalWeather(input)
                "ukcrime" -> deep.ukCrime(input)
                "cve" -> deep.cveLookup(input)
                "breachcat" -> breachCatalogue(input)
                "sanctions" -> sanctions(input)
                "unsanctions" -> unSanctions(input)
                "pgpkey" -> deep.pgpKey(input)
                "commoncrawl" -> deep.commonCrawl(input)
                "photosnear" -> deep.photosNear(input)
                "imagehead" -> handles.imageHead(input)
                "imagehistory" -> handles.imageHistory(input)
                "chess" -> handles.chess(input)
                "lichess" -> handles.lichess(input)
                "codeforces" -> handles.codeforces(input)
                "wikiuser" -> handles.wikimediaUser(input)
                "codeberg" -> handles.codeberg(input)
                "huggingface" -> handles.huggingFace(input)
                "libravatar" -> handles.libravatar(input)
                "disposable" -> disposableAddress(input)
                "ubuntukeys" -> handles.ubuntuKeyserver(input)
                "orcid" -> people.orcid(input)
                "publications" -> people.publications(input)
                "ghname" -> people.codeAccounts(input)
                "bskyname" -> people.socialByName(input)
                "courtrecords" -> people.courtRecords(input)
                "nonprofits" -> people.nonprofits(input)
                "authorbooks" -> people.authorWorks(input)
                "cctvnear" -> cctvNear(input)
                "geocode" -> apis.geocode(input)
                "wikinearby" -> apis.wikiNearby(input)
                "gdelt" -> apis.gdeltNews(input)
                "reverseimage" -> apis.reverseImage(input)
                "exif" -> runCatching { exifReader.fromUrl(input) }.orStop()
                "imagetext" -> imageWords(input)
                else -> Result.failure(IllegalStateException("${tool.name} has no direct implementation."))
            }
        }

    /** Surfaces the matching OSINT Framework category's resources for this
     *  observable, so the whole framework is a connector inside investigations. */
    private fun framework(input: String): Result<String> = runCatching {
        val v = input.trim()
        val isEmail = v.contains('@') && !v.contains(' ')
        val isIp = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(v)
        // Decided before the phone test, and excluded from it. The
        // phone branch came first in the `when` below, so every IP
        // investigation was handed the OSINT Framework's Telephone
        // Numbers category instead of IP Address and Domain Name.
        val isPhone = !isIp && Regex("""^\+?[\d][\d\s\-().]{6,}$""").matches(v)
        val isCoord = Regex("""^-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?$""").matches(v)
        val isImage = Regex(""".*\.(?:jpe?g|png|gif|webp|bmp|tiff?)(?:\?.*)?$""", RegexOption.IGNORE_CASE).matches(v)
        val isUrl = v.startsWith("http", true)
        val isDomain = !isEmail && !isIp && v.contains('.') && !v.contains(' ')
        val isName = v.contains(' ')
        val categories = when {
            isEmail -> listOf("Email Address", "Breach")
            isPhone -> listOf("Telephone Numbers")
            isIp -> listOf("IP Address", "Domain Name")
            isCoord -> listOf("Geolocation", "Maps", "Transport")
            isImage -> listOf("Images", "Videos", "Metadata")
            isUrl -> listOf("Archives", "Domain Name", "Malicious")
            isDomain -> listOf("Domain Name", "IP Address", "Archives")
            isName -> listOf("People Search", "Social Networks", "Search Engines", "Public Records", "Dating")
            else -> listOf("Username", "Social Networks")
        }
        val leaves = com.osint.unified.data.OsintFramework.categoryLeaves(appContext, categories)
        if (leaves.isEmpty()) return@runCatching "No OSINT Framework resources matched."

        // Split by whether the resource can actually be pointed at this
        // target. A link already carrying someone else's example address is
        // noise, so those are filled in where possible and listed plainly
        // where not.
        val runnable = ArrayList<String>()
        val reference = ArrayList<String>()
        leaves.forEach { (name, url) ->
            val filled = com.osint.unified.data.OsintFramework.fillTarget(url, v)
            if (filled != null) runnable.add("$name: $filled") else reference.add("$name: $url")
        }
        buildString {
            append("${leaves.size} OSINT Framework resources for \"$v\"\n")
            if (runnable.isNotEmpty()) {
                append("\n── ${runnable.size} pointed at this target ──\n")
                runnable.forEach { append(it).append("\n") }
            }
            if (reference.isNotEmpty()) {
                append("\n── ${reference.size} general tools (open and paste the target) ──\n")
                reference.take(50).forEach { append(it).append("\n") }
                if (reference.size > 50) {
                    append("…and ${reference.size - 50} more. The directory screen lists them all ")
                    append("and runs any of them against this target.\n")
                }
            }
        }
    }

    /**
     * What a number is, and every way it gets written down.
     *
     * The second half matters more than it looks. Nothing keyless
     * will tell you who owns a telephone number — the services that
     * do are all paid, all account-gated, and this app takes neither
     * — so what is actually useful here is the thing the other
     * sources need: the number in every form a human or a database
     * might have stored it in. A breach dump has 4155550123, a
     * directory has (415) 555-0123, a news story has +1 415-555-0123,
     * and a search for one of those finds none of the others.
     *
     * The verdict also goes on the first line, because that is the
     * line the app reads to decide whether a source found anything,
     * and this one used to open with "Valid: true".
     */
    /**
     * Image geolocation, the keyless way.
     *
     * The three services the user asked for — GeoSpy, GeoInfer,
     * Picarta — all want an account, and this app takes none. But a
     * model that has seen the whole planet is not how most pictures
     * actually get placed: they get placed by reading what is
     * written in them. A shop name, a street sign, a number on a
     * van, the alphabet itself. Every one of those is a string, and
     * this app is full of sources that take strings.
     *
     * So the words come off the picture on the device — the model
     * is in the APK and the photograph never leaves the phone,
     * which for some of these cases is the whole point — and each
     * line is sorted into the kind of thing it is, with what to do
     * with it. The conclusion stays with the person; nothing here
     * claims a location.
     */
    private suspend fun imageWords(input: String): Result<String> = runCatching {
        val url = input.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@runCatching "Give the image's URL."
        }
        val read = imageText.fromUrl(url)
        if (read.isEmpty) {
            return@runCatching "No readable text in this image.\n\n" +
                "That is a result, not a failure: plenty of photographs have " +
                "no writing in them. The visual cues below are what is left, " +
                "and they are what a person uses anyway.\n\n" +
                checklist()
        }
        val clues = com.osint.unified.domain.ImageClues.clues(read)
        val scripts = com.osint.unified.domain.ImageClues.scripts(read)
        buildString {
            append("${read.lines.size} lines of text read from this image")
            append(" (${read.widthPx}x${read.heightPx}, on this device, ")
            append("nothing uploaded).\n")
            if (scripts.isNotEmpty()) {
                append("\nScripts in the frame: ${scripts.joinToString(", ")}\n")
                append("The alphabet narrows the map before a single word is ")
                append("understood, and it is the cheapest cue there is.\n")
            }

            val byKind = clues.groupBy { it.kind }
            val order = listOf(
                com.osint.unified.domain.ImageClues.Kind.COORDINATE,
                com.osint.unified.domain.ImageClues.Kind.DOMAIN,
                com.osint.unified.domain.ImageClues.Kind.EMAIL,
                com.osint.unified.domain.ImageClues.Kind.PHONE,
                com.osint.unified.domain.ImageClues.Kind.ROAD,
                com.osint.unified.domain.ImageClues.Kind.PLATE,
                com.osint.unified.domain.ImageClues.Kind.PLACE
            )
            var leads = 0
            order.forEach { kind ->
                val group = byKind[kind] ?: return@forEach
                append("\n── ${kind.name} ──\n")
                append(group.first().why).append("\n")
                group.take(12).forEach { append("• ${it.value}\n"); leads++ }
                if (group.size > 12) append("…and ${group.size - 12} more.\n")
            }
            if (leads == 0) append("\nNothing in the text sorted into a lead.\n")

            append("\n── EVERY LINE, TOP TO BOTTOM ──\n")
            read.lines.take(60).forEach { append("• ${it.text}\n") }
            if (read.lines.size > 60) {
                append("…and ${read.lines.size - 60} more lines.\n")
            }

            append("\n")
            append(checklist())
        }
    }.orStop()

    private fun checklist(): String = buildString {
        append("── WHAT TO LOOK AT YOURSELF ──\n")
        append("No model does this part, and it is most of the work.\n")
        com.osint.unified.domain.ImageClues.CHECKLIST.forEach {
            append("• ").append(it).append("\n")
        }
        append("\nOnce you have a candidate place, run it as a coordinate: ")
        append("the sun and terrain tool turns a shadow's direction and ")
        append("length into a bearing, a sun elevation and a local time, ")
        append("and the historical weather tool says whether the sky in the ")
        append("picture matches the date it claims.\n")
    }

    private fun phoneInfo(input: String): Result<String> = try {
        val util = PhoneNumberUtil.getInstance()
        val number = util.parse(input, null)
        val region = util.getRegionCodeForNumber(number) ?: "?"
        val intl = util.format(number, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        val national = util.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
        val e164 = util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
        val valid = util.isValidNumber(number)
        val countryName = java.util.Locale("", region).displayCountry.ifBlank { region }
        val sb = StringBuilder()
        if (valid) {
            sb.append("$e164 is a valid $countryName number")
            val kind = util.getNumberType(number).name.lowercase().replace('_', ' ')
            if (kind != "unknown") sb.append(" ($kind)")
            sb.append(".\n")
        } else {
            sb.append("$input is not a valid number under any numbering plan ")
            sb.append("this build knows — it may be mistyped, or from a range ")
            sb.append("not yet allocated.\n")
        }
        sb.append("Country: $countryName ($region), country code +${number.countryCode}\n")
        sb.append("Line type: ${util.getNumberType(number)}\n")

        // Every written form, because a search for one finds none of
        // the others.
        val digits = e164.removePrefix("+")
        val nsn = util.getNationalSignificantNumber(number)
        val forms = linkedSetOf(
            e164,
            intl,
            national,
            digits,
            nsn,
            intl.replace(" ", "-"),
            intl.replace(" ", ""),
            national.replace(" ", "-"),
            national.replace(" ", ""),
            national.replace(" ", ".")
        ).filter { it.isNotBlank() }
        sb.append("\nWritten forms to search for:\n")
        forms.forEach { sb.append("  ").append(it).append("\n") }

        sb.append("\nWho holds this number is not public anywhere keyless: ")
        sb.append("caller-ID and carrier-lookup services all want an account ")
        sb.append("and most want payment. What is above is from the numbering ")
        sb.append("plan itself, offline, and is as much as can be said without one.\n")
        Result.success(sb.toString())
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("Could not parse number (use E.164, e.g. +14155551234): ${e.message}"))
    }

    /** Military sites near a coordinate from OpenStreetMap (public map data). */
    /**
     * Runs the tool directory against the target.
     *
     * Every curated template whose endpoint returns text or JSON is executed
     * here, in parallel, and summarised — those tools genuinely run, with no
     * browser involved. Everything else in the directory that accepts a
     * query in its URL is listed with the target already substituted, so it
     * is one tap from running rather than a bookmark to a blank search box.
     */
    private suspend fun directoryRun(input: String): Result<String> = runCatching {
        val target = input.trim()
        if (target.isEmpty()) throw IllegalArgumentException("Give a target to run the directory against.")
        val observable = com.osint.unified.domain.ObservableRouter().classify(target)
        val type = com.osint.unified.domain.ToolTemplates.typeOf(observable)
        val curated = com.osint.unified.domain.ToolTemplates.curated(type)
        val fetchable = curated.filter { it.fetchable }
        val openable = curated.filterNot { it.fetchable }

        val executed = coroutineScope {
            fetchable.map { t ->
                async {
                    val url = com.osint.unified.domain.ToolTemplates.resolve(t, target)
                    var skipped: String? = null
                    val body = runCatching {
                        // The SEC rejects generic agents outright, and asks
                        // for a descriptive one with contact details.
                        val agent = if (url.contains("sec.gov")) {
                            SecAgent.userAgent(settings.secContactEmail) ?: run {
                                // It was not asked, and that is not the
                                // same as it having nothing to say.
                                //
                                // Without a contact email this returned
                                // null, and null joined the "No data
                                // from" list beside endpoints that were
                                // asked and answered with nothing —
                                // EDGAR reported as empty when it had
                                // never been consulted, and the one
                                // thing the user had to do to fix it
                                // said nowhere.
                                skipped = "needs a contact email in Settings"
                                return@runCatching null
                            }
                        } else {
                            "OSINT-Unified/1.0 (research)"
                        }
                        val request = Request.Builder().url(url)
                            .header("User-Agent", agent)
                            .header("Accept", "application/json, text/plain, */*")
                            .build()
                        client.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                skipped = "HTTP ${resp.code}"
                                null
                            } else {
                                // Twenty endpoints in parallel, each
                                // pointed at a different directory's
                                // idea of a response size.
                                resp.body?.bodyPreview()
                            }
                        }
                    }.orStop().getOrElse {
                        skipped = it.message?.take(60) ?: "did not answer"
                        null
                    }
                    Ran(t.name, url, body, skipped)
                }
            }.map { it.await() }
        }

        val hits = executed.filter { hasContent(it.body) }
        val answeredEmpty = executed.filter { it.why == null && !hasContent(it.body) }
        val notAsked = executed.filter { it.why != null }

        // The wider directory, pointed at this target.
        val derived = runCatching {
            com.osint.unified.domain.ToolTemplates.derived(
                appContext,
                com.osint.unified.domain.ToolTemplates.typeOf(observable)
            )
        }.getOrDefault(emptyList())

        buildString {
            append("Tool directory run — $target (${observable.typeLabel})\n")
            append("${hits.size} of ${fetchable.size} executable endpoints returned data.\n")

            hits.forEach { ran ->
                append("\n▸ ${ran.name}\n")
                val text = ran.body!!.trim()
                val preview = text.lineSequence().filter { it.isNotBlank() }.take(6)
                    .joinToString("\n") { "  " + it.trim().take(180) }
                append(preview)
                append("\n  ${ran.url}\n")
            }

            // Asked and empty, and not asked at all, kept apart.
            if (answeredEmpty.isNotEmpty()) {
                append("\nAsked and had nothing: ")
                append(answeredEmpty.joinToString(", ") { it.name })
                append("\n")
            }
            if (notAsked.isNotEmpty()) {
                append("\nNot asked: ")
                append(notAsked.joinToString(", ") { "${it.name} (${it.why})" })
                append("\n")
            }

            if (openable.isNotEmpty()) {
                append("\n── Run in the browser (target already filled in) ──\n")
                openable.forEach { t ->
                    append("${t.name}: ${com.osint.unified.domain.ToolTemplates.resolve(t, target)}\n")
                }
            }

            // The username rulesets are thousands of profile-URL templates,
            // and printing a page of them here would be a page of guesses:
            // whether an account exists at any of those sites is the
            // username sweep's job, and it checks rather than guessing. A
            // dossier should carry links that lead somewhere, so only the
            // catalogue's own search URLs appear, and only a handful.
            val catalogue = derived.filter { it.source != "username rulesets" }
            if (catalogue.isNotEmpty()) {
                append("\n── Directory searches for this target (${catalogue.size} available) ──\n")
                catalogue.take(12).forEach { t ->
                    append("${t.name}: ${t.resolve(target)}\n")
                }
                if (catalogue.size > 12) {
                    append("\n…and ${catalogue.size - 12} more in the directory screen, ")
                    append("which runs any of them against this target.\n")
                }
            }
        }
    }.orStop()

    /**
     * The catalogue of known public breaches, bundled in the APK.
     *
     * This is the other half of a breach question. Asking whether one
     * address appears in a leak needs a service; asking what has ever
     * leaked from a company — when, how many records, which fields —
     * needs only the catalogue, and that answer holds for every address
     * at that domain. It works with the radio off.
     */
    /**
     * Whether this address is a throwaway, answered offline.
     *
     * It reframes every other line in a dossier about an email.
     * The same address found in three breaches means one thing if
     * somebody has used it for ten years and another if it was
     * minted to get past a signup form — and nothing keyless
     * answers it over the network, because every service that does
     * wants an account. A flat list of domains answers it with the
     * radio off, in one comparison.
     *
     * A hit is not a verdict about a person. Disposable addresses
     * are what careful people use to avoid being tracked across
     * services, which is the same instinct the rest of this app is
     * built around, and the output says so rather than leaving a
     * red flag hanging.
     */
    private fun disposableAddress(input: String): Result<String> = runCatching {
        val address = input.trim().lowercase()
        val domain = address.substringAfterLast('@').removePrefix("www.")
        if (!address.contains('@') || domain.length < 3) {
            return@runCatching "Give an email address."
        }
        val list = runCatching {
            appContext.assets.open("disposable-domains.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toHashSet()
            }
        }.getOrNull()
            ?: return@runCatching "The disposable-domain list is not bundled in " +
                "this build, so nothing is claimed either way."
        if (list.isEmpty()) {
            return@runCatching "The bundled disposable-domain list could not be read."
        }
        // The parent domain too: subdomain throwaways are the
        // common shape, and a list that holds mailinator.com should
        // answer for team.mailinator.com.
        val parentDomain = domain.split('.').let {
            if (it.size > 2) it.takeLast(2).joinToString(".") else domain
        }
        return@runCatching if (domain in list || parentDomain in list) {
            "$domain is a known disposable-mail domain, from a list of " +
                "${list.size} bundled in this build.\n\n" +
                "That changes how everything else about this address reads: an " +
                "account registered with it was probably meant to be temporary, " +
                "and the address is unlikely to tie to a long-lived identity.\n" +
                "It is not a finding about a person. Throwaway addresses are what " +
                "careful people use to avoid being tracked between services."
        } else {
            "$domain is not on the bundled list of ${list.size} known " +
                "disposable-mail domains.\n" +
                "New throwaway services appear constantly and the list is a " +
                "snapshot taken when this build was made, so this is weak " +
                "evidence that the address is durable — not proof of it."
        }
    }

    private fun breachCatalogue(input: String): Result<String> = runCatching {
        val raw = input.trim().lowercase()
        // An address is really a question about its domain.
        val needle = raw.substringAfterLast('@').removePrefix("www.")
        if (needle.length < 3) return@runCatching "Give at least three characters to search the breach catalogue."
        val text = runCatching {
            appContext.assets.open("breaches.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@runCatching "The breach catalogue is not bundled in this build."

        val arr = json.parseToJsonElement(text) as? JsonArray
            ?: return@runCatching "The bundled breach catalogue could not be read."

        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

        val exact = ArrayList<String>()
        val loose = ArrayList<String>()
        var total = 0
        arr.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            total++
            val domain = str(o, "Domain")?.lowercase().orEmpty()
            val title = str(o, "Title") ?: str(o, "Name") ?: return@forEach
            val hitDomain = domain.isNotEmpty() &&
                (domain == needle || domain.endsWith(".$needle") || needle.endsWith(".$domain"))
            val hitName = title.lowercase().contains(needle)
            if (!hitDomain && !hitName) return@forEach

            val classes = (o["DataClasses"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val line = buildString {
                append("• $title")
                if (domain.isNotEmpty()) append("  ($domain)")
                str(o, "BreachDate")?.let { append("\n  Breached: $it") }
                num(o, "PwnCount")?.let { append("   Accounts: ${"%,d".format(Locale.US, it)}") }
                if (classes.isNotEmpty()) append("\n  Exposed: ${classes.joinToString(", ")}")
                if ((o["IsVerified"] as? JsonPrimitive)?.contentOrNull == "false") {
                    append("\n  Unverified by HIBP.")
                }
                str(o, "Description")?.let {
                    append("\n  ${it.replace(Regex("<[^>]+>"), "").take(260)}")
                }
            }
            if (hitDomain) exact.add(line) else loose.add(line)
        }

        val hits = exact + loose.take((12 - exact.size).coerceAtLeast(0))
        if (hits.isEmpty()) {
            "Breach catalogue: no record of a public breach at '$needle' (checked $total known breaches offline)."
        } else {
            buildString {
                append("Known breaches involving '$needle' — ${exact.size} by domain")
                if (loose.isNotEmpty()) append(", ${hits.size - exact.size} by name")
                append(":\n\n")
                append(hits.joinToString("\n\n"))
                append("\n\nAnyone with an address at this domain may appear in these. ")
                append("Source: Have I Been Pwned breach catalogue, bundled in the app.")
            }
        }
    }

    /** Searches the OFAC SDN list bundled into the APK — works offline. */
    private fun sanctions(input: String): Result<String> = runCatching {
        val query = input.trim()
        if (query.length < 3) {
            return@runCatching "Give at least three characters to search the SDN list."
        }
        // Through SanctionsNames, not a copy of it.
        //
        // This had its own three-letter floor, which is right about
        // the Arabic particle "al" and wrong about most of the world:
        // Korean, Chinese and Vietnamese names are mostly two-letter
        // syllables, so "Ri Yong Ho" reduced to ["yong"] and matched
        // every entry containing that syllable, and "Li Na" reduced
        // to nothing and was refused as too short. The shared version
        // uses a particle stop-list instead, and the UN path already
        // used it — so the two lists were answering the same question
        // differently.
        val tokens = com.osint.unified.domain.SanctionsNames.tokens(query)
        if (tokens.isEmpty()) {
            return@runCatching if (com.osint.unified.domain.SanctionsNames.isUnromanised(query)) {
                "The bundled OFAC list is romanised, and \"$query\" has no " +
                    "Latin letters to match against — so this says nothing " +
                    "either way. Try the name as it is transliterated in " +
                    "English-language reporting."
            } else {
                "Give a name to search the SDN list."
            }
        }

        val asOf = runCatching {
            appContext.assets.open("sanctions-asof.txt").bufferedReader().use { it.readText() }.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val hits = LinkedHashMap<String, String>()
        var scanned = 0

        // The primary list, and then ALT.CSV — the aliases, which is where
        // most real matches live and which was not being searched at all.
        var truncated = false
        fun scan(asset: String, viaAlias: Boolean): Boolean =
            runCatching {
                appContext.assets.open(asset).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        scanned++
                        if (hits.size >= 40) {
                            truncated = true
                            break
                        }
                        val cols = splitCsv(line)
                        val name = cols.getOrNull(if (viaAlias) 3 else 1)?.trim('"') ?: continue
                        if (name == "-0-" || name.isBlank()) continue
                        if (!matchesName(tokens, name)) continue
                        val entNum = cols.getOrNull(0)?.trim('"').orEmpty()
                        val detail = buildString {
                            append("• $name")
                            if (viaAlias) append("  [alias]")
                            if (!viaAlias) {
                                cols.getOrNull(2)?.trim('"')?.takeIf { it != "-0-" }
                                    ?.let { append("  [$it]") }
                                cols.getOrNull(3)?.trim('"')?.takeIf { it != "-0-" }
                                    ?.let { append("\n  Programme: $it") }
                                cols.getOrNull(4)?.trim('"')?.takeIf { it != "-0-" }
                                    ?.let { append("\n  Title: $it") }
                                cols.lastOrNull()?.trim('"')?.takeIf { it != "-0-" && it.isNotBlank() }
                                    ?.let { append("\n  ${it.take(220)}") }
                            }
                            if (entNum.isNotBlank()) append("\n  OFAC entity $entNum")
                        }
                        hits.putIfAbsent("$entNum|$name", detail)
                    }
                }
            }.isSuccess

        val readMain = scan("sdn.csv", viaAlias = false)
        val readAlt = scan("sdn-alt.csv", viaAlias = true)

        if (scanned == 0) {
            return@runCatching "The OFAC list could not be read from this build."
        }
        // An absence is only worth stating if both files were read.
        // The aliases are where most real matches live — this code's
        // own comment says so — and the guard was the two scans
        // *combined* having read something, so a build missing the
        // alias file reported a clean result having never looked at
        // it.
        // The cap is checked before each line in both scans, so a
        // primary list that fills it leaves the alias scan breaking
        // on its first line — having read nothing, while still
        // returning success. The aliases are where most real matches
        // live, so saying nothing about that is the one thing this
        // must not do.
        if (truncated) {
            return@runCatching buildString {
                append("OFAC SDN — more than ${hits.size} entries match '$query', ")
                append("which is too many to be a match for anybody.\n")
                append("The alias list was not searched, because the primary ")
                append("list filled the cap first — so this says nothing either ")
                append("way about aliases. Give more of the name.\n\n")
                hits.values.take(10).forEach { append(it).append("\n") }
            }
        }
        if (!readMain || !readAlt) {
            val missing = listOfNotNull(
                if (!readMain) "the primary list" else null,
                if (!readAlt) "the alias list" else null
            ).joinToString(" and ")
            return@runCatching "The OFAC check could not be completed: " +
                "$missing could not be read from this build, so this says " +
                "nothing either way about '$query'."
        }

        val provenance = buildString {
            append("\n\nSource: US Treasury OFAC SDN and alias lists")
            if (asOf != null) append(", snapshot of $asOf")
            append(", bundled in the app.")
            append("\nThis is a name search against a snapshot, not a compliance ")
            append("determination: spelling variants, transliterations and ")
            append("entries added since the snapshot will not appear.")
            if (truncated) {
                // Said out loud. The scan stops at forty and used to
                // stop silently, which made a capped list look like
                // the whole answer — and when the primary file filled
                // the cap the alias file was never reached at all.
                append("\nThis list was cut off at 40 matches, so it is not ")
                append("all of them. Narrow the name to see the rest.")
            }
        }

        if (hits.isEmpty()) {
            "OFAC SDN: no sanctioned entity matching '$query'" +
                (if (asOf != null) " in the snapshot of $asOf" else "") +
                " ($scanned entries searched offline)." + provenance
        } else {
            "OFAC Specially Designated Nationals — ${hits.size} match" +
                (if (hits.size == 1) "" else "es") + " for '$query':\n\n" +
                hits.values.joinToString("\n\n") + provenance
        }
    }

    /**
     * A name, reduced to something comparable.
     *
     * Accents and punctuation vary between the list and whoever typed the
     * query, and neither should decide whether a sanctioned person is
     * found.
     */
    // The name rules live in SanctionsNames, where a test can reach the
    // code that actually runs rather than a copy of it.
    private fun normaliseName(raw: String) = com.osint.unified.domain.SanctionsNames.normalise(raw)

    private fun matchesName(tokens: List<String>, candidate: String) =
        com.osint.unified.domain.SanctionsNames.matches(tokens, candidate)

    /**
     * Searches the UN Security Council consolidated list bundled in the APK.
     * Offline, like the OFAC search — sanctions screening should not depend
     * on a network that may be watched.
     */
    private fun unSanctions(input: String): Result<String> = runCatching {
        val query = input.trim()
        val tokens = normaliseName(query).split(" ").filter { it.isNotBlank() }
        if (query.length < 3 || tokens.isEmpty()) {
            return@runCatching "Give at least three characters to search the UN list."
        }
        val xml = runCatching {
            appContext.assets.open("un-sanctions.xml").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@runCatching "The UN consolidated list is not bundled in this build."

        fun tag(block: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

        fun allTags(block: String, name: String): List<String> =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .findAll(block).map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }.toList()

        val hits = ArrayList<String>()
        listOf("INDIVIDUAL" to "Individual", "ENTITY" to "Entity").forEach { (element, label) ->
            Regex("<$element>(.*?)</$element>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).forEach { m ->
                    if (hits.size >= 30) return@forEach
                    val block = m.groupValues[1]
                    val name = listOfNotNull(
                        tag(block, "FIRST_NAME"), tag(block, "SECOND_NAME"),
                        tag(block, "THIRD_NAME"), tag(block, "FOURTH_NAME")
                    ).joinToString(" ")
                    // Against the names, and only the names.
                    //
                    // This used to be a substring search over the whole XML
                    // block, which holds nationality, designation and a
                    // paragraph of comments — so "ali" matched every person
                    // from Mali and Somalia, and real matches were buried
                    // under them. In the other direction, the four parts of
                    // a name sit in four separate tags, so "ali hassan"
                    // matched nobody at all. Aliases are searched too, which
                    // is where most true matches on this list live.
                    val candidates = listOf(name) +
                        allTags(block, "ALIAS_NAME") +
                        listOfNotNull(tag(block, "NAME_ORIGINAL_SCRIPT")) +
                        allTags(block, "ENTITY_ALIAS")
                    if (candidates.none { matchesName(tokens, it) }) return@forEach
                    hits.add(buildString {
                        append("• ${name.ifBlank { "(unnamed)" }}  [$label]")
                        tag(block, "UN_LIST_TYPE")?.let { append("\n  Regime: $it") }
                        tag(block, "REFERENCE_NUMBER")?.let { append("\n  Reference: $it") }
                        tag(block, "LISTED_ON")?.let { append("\n  Listed: $it") }
                        tag(block, "NATIONALITY")?.let { append("\n  Nationality: ${stripXml(it)}") }
                        tag(block, "DESIGNATION")?.let { append("\n  Designation: ${stripXml(it)}") }
                        tag(block, "COMMENTS1")?.let { append("\n  ${stripXml(it).take(260)}") }
                    })
                }
        }
        if (hits.isEmpty()) {
            "UN consolidated list: no entry whose name matches '$query'.\n" +
                "Searched the listed names and their aliases offline, against " +
                "the copy bundled in this build. A name absent from this list " +
                "is not a clean bill of health: it is one of many lists."
        } else {
            "UN Security Council consolidated sanctions — ${hits.size} match" +
                (if (hits.size == 1) "" else "es") + " for '$query':\n\n" +
                hits.joinToString("\n\n") +
                "\n\nSearched offline against the list bundled in the app. " +
                "A name matching is not an identification — verify against the " +
                "reference number before acting on it."
        }
    }

    private fun stripXml(s: String) =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Cameras mapped in OpenStreetMap near a point.
     *
     * These carry no video feed — OSM records that a camera is there, who
     * it belongs to and which way it points. That is a real answer about
     * the place, which is more than a link to a camera directory's front
     * page ever was.
     */
    /**
     * Cameras OSM knows about, or null when nobody answered.
     *
     * Null and empty are different answers and this returned the same
     * thing for both: a 429 from Overpass — likely, since the same
     * host had just been asked — came back as an empty list and the
     * caller printed "OpenStreetMap has no surveillance cameras
     * mapped within 1 km either", which is a statement about a street
     * made from a request that was refused.
     */
    private fun surveillanceNear(lat: Double, lon: Double): List<String>? {
        val query = "[out:json][timeout:40];" +
            "(node(around:1000,$lat,$lon)[man_made=surveillance];" +
            "way(around:1000,$lat,$lon)[man_made=surveillance];);out center 60;"
        val elements = when (val answer = Overpass.query(query, client)) {
            is Overpass.Answer.Unreachable -> return null
            is Overpass.Answer.Ok -> answer.elements
        }
        return elements.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val centre = o["center"] as? JsonObject
            val eLat = ((centre ?: o)["lat"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: return@mapNotNull null
            val eLon = ((centre ?: o)["lon"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: return@mapNotNull null
            val tags = o["tags"] as? JsonObject
            fun tag(k: String) = (tags?.get(k) as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull
            val type = (o["type"] as? JsonPrimitive)?.contentOrNull ?: "node"
            buildString {
                append("• ")
                append(tag("name") ?: tag("surveillance:type") ?: "camera")
                tag("operator")?.let { append(" · $it") }
                tag("camera:type")?.let { append(" · $it") }
                tag("surveillance")?.let { append(" · covers $it") }
                tag("camera:mount")?.let { append(" · $it mounted") }
                append("\n  %.1f km — %.5f, %.5f".format(Locale.US, 
                    haversineKm(lat, lon, eLat, eLon), eLat, eLon
                ))
                if (id != null) append("\n  https://www.openstreetmap.org/$type/$id")
            }
        }
    }

    /** Minimal CSV splitter that respects quoted fields. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    /** Public traffic cameras near a coordinate, with live still-image links. */
    /**
     * Cameras their operators meant to be public.
     *
     * The ask was for a live view of anywhere in the world, and
     * there is an honest version of that and a dishonest one. The
     * dishonest one is Insecam and default-credential IP cameras:
     * those are private people's cameras reached because somebody
     * left them open, which is unauthorised access to a stranger's
     * system and very often a camera pointed at a private
     * individual. This app's own first-run screen rules both of
     * those out, so they stay out.
     *
     * The honest one is the networks that publish their own: roads
     * authorities, cities, national transport agencies. Four of
     * them answer with no key and no account, and between them
     * they cover California, New York, London and Finland. They
     * are fetched by where you are looking rather than all at once
     * — London's list alone is a megabyte, and downloading it to
     * look at Tokyo is data spent on nothing.
     */
    private suspend fun cctvNear(input: String): Result<String> = runCatching {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            throw IllegalArgumentException("Expected coordinates as 'lat, lon'.")
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        // The shared instance, through the cached client.
        //
        // This built a new CctvDataSource on every call and handed it
        // `client`, which defeated two caches at once: the source
        // keeps its six-hour inventory in instance fields, so a fresh
        // instance is always cold, and the explicit client overrode
        // the feed client — the only one with a disk cache — so there
        // was no conditional request either. Every invocation pulled
        // nine Caltrans district inventories in full. The largest
        // avoidable mobile-data cost in the app.
        val cams = cctv.fetch()
        if (cams.isEmpty()) return@runCatching "Public camera inventories could not be reached right now."
        val near = cams.map { it to haversineKm(lat, lon, it.lat, it.lon) }
            .filter { it.second <= 50 }
            .sortedBy { it.second }
            .take(25)
        if (near.isEmpty()) {
            // The old fallback printed two camera-directory homepages and a
            // sentence describing an OpenStreetMap query. None of the three
            // was pointed at the place being asked about, so none of them
            // answered anything — they were links for the sake of having
            // links. OSM actually holds mapped cameras, so the query is run
            // rather than described.
            val mapped = runCatching { surveillanceNear(lat, lon) }.orStop().getOrNull()
            return@runCatching buildString {
                append("No public traffic-camera feed within 50 km of %.4f, %.4f ".format(Locale.US, lat, lon))
                append("(live still images come from the operators that publish ")
                append("them keylessly: Caltrans in California, NYC DOT, ")
                append("Transport for London and Fintraffic in Finland).\n")
                if (mapped == null) {
                    append("OpenStreetMap could not be asked about mapped cameras here, ")
                    append("so nothing is claimed about those either.")
                } else if (mapped.isEmpty()) {
                    append("OpenStreetMap has no surveillance cameras mapped within 1 km either.")
                } else {
                    append("\nOpenStreetMap has ${mapped.size} surveillance camera")
                    append(if (mapped.size == 1) "" else "s")
                    append(" mapped within 1 km — position only, no feed:\n")
                    mapped.take(25).forEach { append("\n$it") }
                }
            }
        }
        buildString {
            append("Public cameras within 50 km of %.4f, %.4f — ${near.size} found:\n".format(Locale.US, lat, lon))
            near.forEach { (cam, km) ->
                append("\n• ${cam.label}")
                append("\n  %.1f km — %.5f, %.5f".format(Locale.US, km, cam.lat, cam.lon))
                cam.url?.let { append("\n  Live still: $it") }
            }
        }
    }.orStop()

    private fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    /**
     * Physical-infrastructure recon around a coordinate, worldwide, from
     * OpenStreetMap: surveillance cameras, communication masts, helipads,
     * border control, substations, bunkers and fuel.
     *
     * This is the global counterpart to the Caltrans camera layer — OSM has
     * surveillance mapped almost everywhere, which matters when you are
     * working out what would have seen something happen.
     */
    private fun osmRecon(input: String): Result<String> {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return Result.failure(IllegalArgumentException("Expected coordinates as 'lat, lon'."))
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        val r = 2000
        val categories = listOf(
            "man_made=surveillance" to "Surveillance camera",
            "man_made=mast" to "Mast",
            "man_made=communications_tower" to "Comms tower",
            "aeroway=helipad" to "Helipad",
            "barrier=border_control" to "Border control",
            "power=substation" to "Substation",
            "military=bunker" to "Bunker",
            "amenity=fuel" to "Fuel",
            "amenity=police" to "Police",
            "amenity=hospital" to "Hospital"
        )
        val clauses = categories.joinToString("") { (tag, _) ->
            val (k, v) = tag.split("=")
            "nwr(around:$r,$lat,$lon)[\"$k\"=\"$v\"];"
        }
        val q = "[out:json][timeout:40];($clauses);out center 300;"
        // Through the shared client, with the mirrors and the
        // 200-with-non-JSON check.
        //
        // This posted straight at overpass-api.de and read the body
        // with a runCatching that turned null into "No mapped
        // infrastructure within 2000m". A timed-out Overpass answers
        // 200 with a plain-text "runtime error: Query timed out",
        // which is exactly that path — so under load the
        // infrastructure scan reported an empty street as fact, and
        // overpass-api.de was the mirror failing while two others
        // answered. This is the last of the six call sites that was
        // still doing it by hand.
        return when (val answer = Overpass.query(q, client)) {
            is Overpass.Answer.Unreachable -> Result.success(
                "Overpass could not be reached for $lat, $lon, so nothing is " +
                    "claimed about what is mapped there (${answer.why})."
            )
            is Overpass.Answer.Ok -> {
                val els = answer.elements
                if (els.isEmpty()) {
                    return Result.success("No mapped infrastructure within ${r}m of $lat, $lon.")
                }
                val grouped = LinkedHashMap<String, MutableList<String>>()
                els.forEach { e ->
                    val o = e as? JsonObject ?: return@forEach
                    val tags = o["tags"] as? JsonObject
                    fun t(k: String) = (tags?.get(k) as? JsonPrimitive)?.contentOrNull
                    val label = categories.firstOrNull { (tag, _) ->
                        val (k, v) = tag.split("=")
                        t(k) == v
                    }?.second ?: return@forEach
                    val c = o["center"] as? JsonObject
                    val la = ((c?.get("lat") ?: o["lat"]) as? JsonPrimitive)?.contentOrNull
                    val lo = ((c?.get("lon") ?: o["lon"]) as? JsonPrimitive)?.contentOrNull
                    val name = t("name") ?: t("operator") ?: t("surveillance:type") ?: ""
                    val detail = buildString {
                        append("• ")
                        append(if (name.isNotBlank()) name else "(unnamed)")
                        t("surveillance")?.let { append(" · $it") }
                        t("camera:mount")?.let { append(" · mount $it") }
                        t("camera:direction")?.let { append(" · facing $it°") }
                        t("tower:type")?.let { append(" · $it") }
                        t("operator")?.takeIf { it != name }?.let { append(" · $it") }
                        if (la != null && lo != null) append("\n  $la, $lo")
                    }
                    grouped.getOrPut(label) { mutableListOf() }.add(detail)
                }
                if (grouped.isEmpty()) {
                    return Result.success("No mapped infrastructure within ${r}m of $lat, $lon.")
                }
                val out = buildString {
                    append("Infrastructure within ${r}m of %.5f, %.5f\n".format(Locale.US, lat, lon))
                    append("${grouped.values.sumOf { it.size }} mapped features, ")
                    append("from OpenStreetMap via ${answer.from}.\n")
                    grouped.forEach { (label, items) ->
                        append("\n${label.uppercase()} (${items.size}):\n")
                        items.take(40).forEach { append(it).append("\n") }
                    }
                    append("\nOSM coverage varies by area; absence here is not evidence of absence.")
                }
                Result.success(out)
            }
        }
    }

    private fun militarySites(input: String): Result<String> {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return Result.failure(IllegalArgumentException("Expected coordinates as 'lat, lon'."))
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        // Sixty seconds, not thirty. A 25 km nwr[military] union is
        // one of the heaviest things anyone asks Overpass for, and
        // the server's own timeout was the thing being hit.
        val q = "[out:json][timeout:60];(nwr(around:25000,$lat,$lon)[military];" +
            "nwr(around:25000,$lat,$lon)[landuse=military];);out center 80;"
        return when (val answer = Overpass.query(q, client)) {
            is Overpass.Answer.Unreachable -> Result.success(
                "Overpass could not be reached for military sites near " +
                    "$lat, $lon, so nothing is claimed either way (${answer.why})."
            )
            is Overpass.Answer.Ok -> {
                val lines = answer.elements.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val tags = o["tags"] as? JsonObject
                    fun t(k: String) = (tags?.get(k) as? JsonPrimitive)?.contentOrNull
                    val name = t("name") ?: t("military") ?: return@mapNotNull null
                    val kind = t("military") ?: t("landuse") ?: ""
                    val c = o["center"] as? JsonObject
                    val la = ((c?.get("lat") ?: o["lat"]) as? JsonPrimitive)?.contentOrNull
                    val lo = ((c?.get("lon") ?: o["lon"]) as? JsonPrimitive)?.contentOrNull
                    "• $name${if (kind.isNotBlank()) " ($kind)" else ""}" +
                        if (la != null && lo != null) "\n  Coords: $la, $lo" else ""
                }
                // "0 military-tagged sites" was being read as a
                // finding, because it is not the word "no".
                if (lines.isEmpty()) {
                    Result.success(
                        "No military-tagged sites within 25 km of $lat, $lon " +
                            "in OpenStreetMap."
                    )
                } else {
                    Result.success(
                        "${lines.size} military-tagged sites within 25 km " +
                            "(OSM via ${answer.from}):\n" + lines.joinToString("\n")
                    )
                }
            }
        }
    }

    private fun overpass(input: String): Result<String> {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return Result.failure(IllegalArgumentException("Expected coordinates as 'lat, lon'."))
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        val query = "[out:json][timeout:40];(node(around:500,$lat,$lon)[amenity];" +
            "node(around:500,$lat,$lon)[man_made];);out body 60;"
        return when (val answer = Overpass.query(query, client)) {
            is Overpass.Answer.Unreachable -> Result.success(
                "Overpass could not be reached for $lat, $lon, so nothing is " +
                    "claimed about what is mapped there (${answer.why})."
            )
            is Overpass.Answer.Ok -> {
                // A sentence in front, because the classifier reads
                // the first line and raw JSON begins with a brace.
                // "No mapped features" was that sentence and it
                // matched no absence pattern either, so an empty
                // result was still filed as a finding.
                val els = answer.elements
                if (els.isEmpty()) {
                    Result.success(
                        "No mapped places or structures within 500 m of $lat, $lon."
                    )
                } else {
                    // Readable lines rather than the raw body. This
                    // used to append the whole Overpass document —
                    // every tag of every feature, kilobytes of JSON —
                    // under a one-line summary, into the dossier and
                    // into the card.
                    val lines = els.mapNotNull { e ->
                        val o = e as? JsonObject ?: return@mapNotNull null
                        val tags = o["tags"] as? JsonObject
                        fun t(k: String) = (tags?.get(k) as? JsonPrimitive)?.contentOrNull
                        val name = t("name")
                        val kind = t("amenity") ?: t("man_made") ?: return@mapNotNull null
                        val la = (o["lat"] as? JsonPrimitive)?.contentOrNull
                        val lo = (o["lon"] as? JsonPrimitive)?.contentOrNull
                        buildString {
                            append("• ").append(name ?: kind)
                            if (name != null) append(" ($kind)")
                            if (la != null && lo != null) append("\n  $la, $lo")
                            t("operator")?.let { append("\n  Operator: $it") }
                            t("addr:street")?.let { street ->
                                append("\n  ")
                                t("addr:housenumber")?.let { append("$it ") }
                                append(street)
                            }
                        }
                    }
                    Result.success(
                        "${els.size} mapped features within 500 m of $lat, $lon " +
                            "(OSM via ${answer.from}):\n" + lines.joinToString("\n")
                    )
                }
            }
        }
    }
}

/** Picks the right executor for a tool based on its [ExecutionMode]. */
class ExecutorFactory(
    private val cloud: ToolExecutor,
    private val local: ToolExecutor,
    private val direct: ToolExecutor
) {
    fun forTool(tool: Tool): ToolExecutor =
        when (tool.executionMode) {
            ExecutionMode.CLOUD -> cloud
            ExecutionMode.LOCAL -> local
            ExecutionMode.DIRECT -> direct
        }
}
