package com.osint.unified.domain.executor

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.osint.unified.data.AppSettings
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Tool
import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/**
 * Executes a single tool against an input and returns its raw output text.
 * Raw output is intentionally preserved verbatim so nothing is lost before
 * parsing or export.
 */
interface ToolExecutor {
    suspend fun execute(tool: Tool, input: String): Result<String>
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
                val request = Request.Builder()
                    .url("$base/${tool.id}")
                    .post(payload.toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(text)
                    else Result.failure(IOException("HTTP ${resp.code}: ${text.take(300)}"))
                }
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
        withContext(Dispatchers.IO) {
            val binary = File(nativeLibraryDir, "lib${tool.id}.so")
            if (!binary.exists()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "${tool.name} is a local tool but its binary isn't bundled in this build. " +
                            "It ships as jniLibs/arm64-v8a/lib${tool.id}.so once added to the CI build step."
                    )
                )
            }
            try {
                val command = buildCommand(tool, input, binary.absolutePath)
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .apply {
                        directory(File(workDir))
                        // Tools expect a writable HOME/TMP for config + caches.
                        environment()["HOME"] = workDir
                        environment()["TMPDIR"] = workDir
                    }
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(tool.timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    Result.failure(IOException("${tool.name} timed out after ${tool.timeoutMs} ms"))
                } else {
                    Result.success(output)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Builds the command line. When a workflow passes a multi-line host list
     * (e.g. subfinder output into httpx), it's written to a file and fed via
     * `-l` instead of `-u`.
     */
    private fun buildCommand(tool: Tool, input: String, binPath: String): List<String> {
        val cmd = mutableListOf(binPath)
        if (tool.id == "httpx" && input.contains('\n')) {
            val listFile = File(workDir, "httpx_targets.txt").apply { writeText(input) }
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
        "amass" -> listOf("enum", "-passive", "-d", input, "-json", "/dev/stdout")
        "httpx" -> listOf("-u", input, "-json", "-silent", "-title", "-tech-detect", "-status-code")
        "exiftool" -> listOf("-j", "-a", "-u", "-g1", input)
        else -> listOf(input)
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
    private val apis: OsintApis = OsintApis(),
    private val deep: DeepApis = DeepApis(),
    private val client: OkHttpClient = Http.client
) : ToolExecutor {

    private val formUrlEncoded = "application/x-www-form-urlencoded".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(tool: Tool, input: String): Result<String> =
        withContext(Dispatchers.IO) {
            when (tool.id) {
                "overpass_turbo" -> overpass(input)
                "milsites" -> militarySites(input)
                "osmrecon" -> osmRecon(input)
                "sherlock", "maigret", "blackbird" -> runCatching { usernameChecker.check(input) }
                "emailaccounts" -> runCatching {
                    usernameChecker.check(input.substringBefore('@').substringBefore('+').trim())
                }
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
                "socialsweep" -> runCatching { deep.socialSweep(input) }
                "toolrun" -> directoryRun(input)
                "emailperm" -> deep.emailPermutations(input)
                "typosquat" -> runCatching { deep.typosquat(input) }
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
                "cctvnear" -> cctvNear(input)
                "geocode" -> apis.geocode(input)
                "wikinearby" -> apis.wikiNearby(input)
                "gdelt" -> apis.gdeltNews(input)
                "reverseimage" -> apis.reverseImage(input)
                "exif" -> runCatching { exifReader.fromUrl(input) }
                else -> Result.failure(IllegalStateException("${tool.name} has no direct implementation."))
            }
        }

    /** Surfaces the matching OSINT Framework category's resources for this
     *  observable, so the whole framework is a connector inside investigations. */
    private fun framework(input: String): Result<String> = runCatching {
        val v = input.trim()
        val isEmail = v.contains('@') && !v.contains(' ')
        val isPhone = Regex("""^\+?[\d][\d\s\-().]{6,}$""").matches(v)
        val isIp = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(v)
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

    private fun phoneInfo(input: String): Result<String> = try {
        val util = PhoneNumberUtil.getInstance()
        val number = util.parse(input, null)
        val region = util.getRegionCodeForNumber(number) ?: "?"
        val intl = util.format(number, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        val countryName = java.util.Locale("", region).displayCountry.ifBlank { region }
        val sb = StringBuilder()
        sb.append("Valid: ${util.isValidNumber(number)}\n")
        sb.append("Country: $countryName ($region)\n")
        sb.append("Country code: +${number.countryCode}\n")
        sb.append("Line type: ${util.getNumberType(number)}\n")
        sb.append("International: $intl\n")
        sb.append("National: ${util.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)}\n")
        val enc = java.net.URLEncoder.encode("\"$intl\"", "UTF-8")
        sb.append("\nSearch dorks:\n")
        sb.append("https://www.google.com/search?q=$enc\n")
        sb.append("https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}\n")
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
                    val body = runCatching {
                        // The SEC rejects generic agents outright, and asks
                        // for a descriptive one with contact details.
                        val agent = if (url.contains("sec.gov")) {
                            SEC_USER_AGENT
                        } else {
                            "OSINT-Unified/1.0 (research)"
                        }
                        val request = Request.Builder().url(url)
                            .header("User-Agent", agent)
                            .header("Accept", "application/json, text/plain, */*")
                            .build()
                        client.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) null else resp.body?.string()
                        }
                    }.getOrNull()
                    Triple(t.name, url, body)
                }
            }.map { it.await() }
        }

        val hits = executed.filter { !it.third.isNullOrBlank() && it.third!!.length > 20 }
        val empty = executed.filter { it.third.isNullOrBlank() || it.third!!.length <= 20 }

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

            hits.forEach { (name, url, body) ->
                append("\n▸ $name\n")
                val text = body!!.trim()
                val preview = text.lineSequence().filter { it.isNotBlank() }.take(6)
                    .joinToString("\n") { "  " + it.trim().take(180) }
                append(preview)
                append("\n  $url\n")
            }

            if (empty.isNotEmpty()) {
                append("\nNo data from: ${empty.joinToString(", ") { it.first }}\n")
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
    }

    /**
     * The catalogue of known public breaches, bundled in the APK.
     *
     * This is the other half of a breach question. Asking whether one
     * address appears in a leak needs a service; asking what has ever
     * leaked from a company — when, how many records, which fields —
     * needs only the catalogue, and that answer holds for every address
     * at that domain. It works with the radio off.
     */
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
                num(o, "PwnCount")?.let { append("   Accounts: ${"%,d".format(it)}") }
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
        val needle = input.trim().lowercase()
        if (needle.length < 3) return@runCatching "Give at least three characters to search the SDN list."
        val hits = ArrayList<String>()
        var scanned = 0
        appContext.assets.open("sdn.csv").bufferedReader().useLines { lines ->
            for (line in lines) {
                scanned++
                if (!line.lowercase().contains(needle)) continue
                // ent_num,"NAME",type,"PROGRAM",title,…,remarks
                val cols = splitCsv(line)
                val name = cols.getOrNull(1)?.trim('"') ?: continue
                val type = cols.getOrNull(2)?.trim('"')?.takeIf { it != "-0-" }
                val program = cols.getOrNull(3)?.trim('"')?.takeIf { it != "-0-" }
                val title = cols.getOrNull(4)?.trim('"')?.takeIf { it != "-0-" }
                val remarks = cols.lastOrNull()?.trim('"')?.takeIf { it != "-0-" }
                hits.add(buildString {
                    append("• $name")
                    if (type != null) append("  [$type]")
                    if (program != null) append("\n  Programme: $program")
                    if (title != null) append("\n  Title: $title")
                    if (!remarks.isNullOrBlank()) append("\n  ${remarks.take(220)}")
                })
                if (hits.size >= 40) break
            }
        }
        if (hits.isEmpty()) {
            "OFAC SDN: no sanctioned entity matching '$input' (searched $scanned entries offline)."
        } else {
            "OFAC Specially Designated Nationals — ${hits.size} match" +
                (if (hits.size == 1) "" else "es") + " for '$input':\n\n" + hits.joinToString("\n\n") +
                "\n\nSource: US Treasury OFAC SDN list, bundled in the app."
        }
    }

    /**
     * Searches the UN Security Council consolidated list bundled in the APK.
     * Offline, like the OFAC search — sanctions screening should not depend
     * on a network that may be watched.
     */
    private fun unSanctions(input: String): Result<String> = runCatching {
        val needle = input.trim().lowercase()
        if (needle.length < 3) return@runCatching "Give at least three characters to search the UN list."
        val xml = runCatching {
            appContext.assets.open("un-sanctions.xml").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@runCatching "The UN consolidated list is not bundled in this build."

        fun tag(block: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

        val hits = ArrayList<String>()
        listOf("INDIVIDUAL" to "Individual", "ENTITY" to "Entity").forEach { (element, label) ->
            Regex("<$element>(.*?)</$element>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).forEach { m ->
                    if (hits.size >= 30) return@forEach
                    val block = m.groupValues[1]
                    if (!block.lowercase().contains(needle)) return@forEach
                    val name = listOfNotNull(
                        tag(block, "FIRST_NAME"), tag(block, "SECOND_NAME"),
                        tag(block, "THIRD_NAME"), tag(block, "FOURTH_NAME")
                    ).joinToString(" ")
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
            "UN consolidated list: nothing matching '${input.trim()}'."
        } else {
            "UN Security Council consolidated sanctions — ${hits.size} match" +
                (if (hits.size == 1) "" else "es") + " for '${input.trim()}':\n\n" +
                hits.joinToString("\n\n") +
                "\n\nSearched offline against the list bundled in the app."
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
    private fun surveillanceNear(lat: Double, lon: Double): List<String> {
        val query = "[out:json][timeout:25];" +
            "(node(around:1000,$lat,$lon)[man_made=surveillance];" +
            "way(around:1000,$lat,$lon)[man_made=surveillance];);out center 60;"
        val body = ("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toRequestBody(formUrlEncoded)
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "OSINT-Unified/1.0 (research)")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val text = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        val elements = (json.parseToJsonElement(text).jsonObject["elements"] as? JsonArray)
            ?: return emptyList()
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
                append("\n  %.1f km — %.5f, %.5f".format(
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
    private suspend fun cctvNear(input: String): Result<String> = runCatching {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            throw IllegalArgumentException("Expected coordinates as 'lat, lon'.")
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        val cams = com.osint.unified.data.CctvDataSource(client).fetch()
        if (cams.isEmpty()) return@runCatching "No public camera inventory reachable right now."
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
            val mapped = runCatching { surveillanceNear(lat, lon) }.getOrNull().orEmpty()
            return@runCatching buildString {
                append("No public traffic-camera feed within 50 km of %.4f, %.4f ".format(lat, lon))
                append("(live still images today are Caltrans, California only).\n")
                if (mapped.isEmpty()) {
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
            append("Public cameras within 50 km of %.4f, %.4f — ${near.size} found:\n".format(lat, lon))
            near.forEach { (cam, km) ->
                append("\n• ${cam.label}")
                append("\n  %.1f km — %.5f, %.5f".format(km, cam.lat, cam.lon))
                cam.url?.let { append("\n  Live still: $it") }
            }
        }
    }

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
        return try {
            val body = ("data=" + java.net.URLEncoder.encode(q, "UTF-8")).toRequestBody(formUrlEncoded)
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .header("Accept", "application/json")
                .post(body).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.failure(IOException("HTTP ${resp.code}"))
                val els = runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(text).jsonObject["elements"]?.jsonArray
                }.getOrNull()
                if (els.isNullOrEmpty()) {
                    return Result.success("No mapped infrastructure within ${r}m of $lat, $lon.")
                }
                val grouped = LinkedHashMap<String, MutableList<String>>()
                els.forEach { e ->
                    val o = e.jsonObject
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
                    append("Infrastructure within ${r}m of %.5f, %.5f\n".format(lat, lon))
                    append("${grouped.values.sumOf { it.size }} mapped features, from OpenStreetMap.\n")
                    grouped.forEach { (label, items) ->
                        append("\n${label.uppercase()} (${items.size}):\n")
                        items.take(40).forEach { append(it).append("\n") }
                    }
                    append("\nOSM coverage varies by area; absence here is not evidence of absence.")
                }
                Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun militarySites(input: String): Result<String> {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return Result.failure(IllegalArgumentException("Expected coordinates as 'lat, lon'."))
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        val q = "[out:json][timeout:30];(nwr(around:25000,$lat,$lon)[military];" +
            "nwr(around:25000,$lat,$lon)[landuse=military];);out center 80;"
        return try {
            val body = ("data=" + java.net.URLEncoder.encode(q, "UTF-8")).toRequestBody(formUrlEncoded)
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .header("Accept", "application/json")
                .post(body).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.failure(IOException("HTTP ${resp.code}"))
                val els = runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(text).jsonObject["elements"]?.jsonArray
                }.getOrNull()
                if (els == null || els.isEmpty()) return Result.success("No military-tagged sites within 25 km.")
                val lines = els.mapNotNull { e ->
                    val o = e.jsonObject
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
                Result.success("${lines.size} military-tagged sites within 25 km (OSM):\n" + lines.joinToString("\n"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun overpass(input: String): Result<String> {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return Result.failure(IllegalArgumentException("Expected coordinates as 'lat, lon'."))
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        val query = "[out:json][timeout:25];(node(around:500,$lat,$lon)[amenity];node(around:500,$lat,$lon)[man_made];);out body 60;"
        return try {
            val body = ("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toRequestBody(formUrlEncoded)
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .header("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(text)
                else Result.failure(IOException("HTTP ${resp.code}: ${text.take(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
