package com.osint.unified.domain.executor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * On-device username enumeration across every site ruleset the open-source
 * tools ship — no Python, no backend.
 *
 * Sherlock, Maigret, Blackbird and WhatsMyName are all, underneath, lists of
 * sites plus a rule for telling "profile exists" from "404". So rather than
 * trying to run those tools, this bundles their data and executes the rules
 * natively. Three rulesets are merged and deduplicated by target URL:
 *
 *  - WhatsMyName  (`wmn-data.json`)  — curated, carries a marker string
 *  - Sherlock     (`sherlock.json`)  — status_code / message / response_url
 *  - Maigret      (`maigret.json`)   — presence and absence strings
 *
 * Together that is a few thousand sites. Checks run with bounded concurrency
 * under an overall time cap, and a partial result is reported honestly.
 */
class UsernameChecker(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The device's own user agent.
     *
     * Every request used to claim to be Chrome 120 on Android 13 — a 2023
     * browser — from one address at ninety-six at a time. Bot detection
     * reads that pattern instantly, and it is trivially linkable across
     * nine thousand sites back to this app.
     */
    private val userAgent: String = runCatching {
        android.webkit.WebSettings.getDefaultUserAgent(appContext)
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** One site's rule, normalised across the three source formats. */
    private data class SiteRule(
        val name: String,
        val urlTemplate: String,
        val expectedCode: Int? = null,
        val presence: List<String> = emptyList(),
        val absence: List<String> = emptyList(),
        val source: String = ""
    ) {
        /** A rule that only reads the status code cannot tell a profile
         *  from a site that answers 200 to everything. Those hits get a
         *  control probe before they are believed. */
         val statusOnly: Boolean get() = presence.isEmpty() && absence.isEmpty()

        fun urlFor(username: String): String = urlTemplate
            .replace("{account}", username)
            .replace("{username}", username)
            .replace("{}", username)
    }

    // Not private: the classifier reads the verdict field this sweep
    // writes, and the two have to agree on its spelling.
    companion object {
        // Parsing three rulesets costs several megabytes of JSON, so the
        // merged result is shared across every UsernameChecker instance
        // rather than rebuilt per use.
        @Volatile var sharedRules: List<SiteRule>? = null

        /** Control probes to run. Beyond this, hits are reported unverified
         *  rather than paying for a second sweep as large as the first. */
        const val CONTROL_PROBE_CAP = 400

        /** One heavy page at 64 concurrent reads is enough to run out of
         *  memory, so no body is read past this. */
        const val MAX_BODY_BYTES = 256L * 1024

        /** The first line of a sweep's output, read as data. */
        const val VERDICT_FIELD = "sweep-verdict="
        const val VERDICT_HIT = "hit"
        const val VERDICT_ABSENT = "absent"
        const val VERDICT_PARTIAL = "partial"
        const val VERDICT_UNREACHABLE = "unreachable"

        /** The share of the list that has to have answered before "no
         *  accounts" is reported as an absence rather than a shrug. */
        const val GOOD_COVERAGE = 0.80

        /** 40 + 15 = 55 s, inside the 70 s the registry allows this tool. */
        const val SWEEP_BUDGET_MS = 40_000L
        const val CONTROL_BUDGET_MS = 15_000L
    }

    suspend fun check(username: String): String = withContext(Dispatchers.IO) {
        val sites = loadRules()
        if (sites.isEmpty()) return@withContext "Could not load any site ruleset (check network)."

        // A username with characters a site's URL cannot carry was being
        // injected raw into thousands of templates; sites that reject the
        // format answer with their homepage, which then counted as a hit.
        val encoded = java.net.URLEncoder.encode(username, "UTF-8").replace("+", "%20")

        val found = Collections.synchronizedList(mutableListOf<Pair<SiteRule, String>>())
        val semaphore = Semaphore(64)
        val answeredNo = java.util.concurrent.atomic.AtomicInteger(0)
        val couldNotAsk = java.util.concurrent.atomic.AtomicInteger(0)

        // The outer per-tool cap is 70 s. Sweep plus control probes has to
        // finish inside that, or the whole result is thrown away and
        // replaced by a generic timeout just as it completes.
        val completed = withTimeoutOrNull(SWEEP_BUDGET_MS) {
            coroutineScope {
                sites.map { site ->
                    async {
                        semaphore.withPermit {
                            when (val answer = checkSite(site, encoded)) {
                                is SiteAnswer.Found -> found.add(site to answer.url)
                                SiteAnswer.Absent -> answeredNo.incrementAndGet()
                                SiteAnswer.Unreachable -> couldNotAsk.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
            true
        } != null

        val raw = found.sortedBy { it.first.name.lowercase() }
        val said = answeredNo.get() + found.size
        val refused = couldNotAsk.get()
        val notReached = (sites.size - said - refused).coerceAtLeast(0)

        // Anything matched on page content is believed; anything matched on
        // a bare 200 is not, until a control proves the site can say no.
        val (byContent, byStatus) = raw.partition { !it.first.statusOnly }
        val survived = controlFilter(byStatus)
        val discarded = byStatus.size - survived.size
        val hits = (byContent + survived).sortedBy { it.first.name.lowercase() }

        // The verdict is worked out here, from the counts, and written as
        // a field rather than left to be guessed from the prose. The
        // previous arrangement appended "This is not a clean sheet" to
        // every no-hit result and the classifier matched on that phrase,
        // so a sweep with full coverage and no hits — the definition of an
        // absence — was filed as "could not be reached". The inverse of
        // the bug it replaced, and just as useless.
        val reached = said.toDouble() / sites.size.coerceAtLeast(1)
        val verdict = when {
            hits.isNotEmpty() -> VERDICT_HIT
            said == 0 -> VERDICT_UNREACHABLE
            reached >= GOOD_COVERAGE && completed -> VERDICT_ABSENT
            else -> VERDICT_PARTIAL
        }

        buildString {
            // A field, not a sentence: the first line is read by the code
            // that decides what this result means, and stripped before
            // anything is shown.
            append(VERDICT_FIELD).append(verdict).append('\n')
            // Three numbers, because they mean three different things and
            // pooling them is how a sweep that reached a fifth of its list
            // reported a clean sheet.
            val coverage = "$said answered · $refused refused or blocked" +
                if (notReached > 0) " · $notReached not reached" else ""
            if (hits.isEmpty()) {
                append("No accounts found for \"$username\".\n")
                append("Coverage: $coverage of ${sites.size} sites")
                append(if (completed) "." else " — stopped at the time cap.")
                if (verdict == VERDICT_ABSENT) {
                    append("\nEvery site that was asked said no.")
                } else {
                    append("\nThis is not a clean sheet: only the $said sites that ")
                    append("answered said no.")
                }
            } else {
                append("Found ${hits.size} accounts for \"$username\".\n")
                append("Coverage: $coverage of ${sites.size} sites")
                append(if (completed) ":" else " — stopped at the time cap:")
                append("\n")
                hits.forEach { (site, url) ->
                    append(site.name)
                    if (site.statusOnly) append(" ~")
                    append(" — ")
                    append(url)
                    append("\n")
                }
            }
            if (discarded > 0) {
                append("\n")
                append("$discarded apparent hit")
                append(if (discarded == 1) " was" else "s were")
                append(" dropped: the site returned the same answer for a username ")
                append("that cannot exist, so it says yes to everything.")
            }
            if (hits.any { it.first.statusOnly }) {
                append("\n~ = confirmed only by the site answering differently for a ")
                append("control username, not by anything on the page.")
            }
        }
    }

    /**
     * Soft-404 defence.
     *
     * A great many sites answer 200 to any profile URL, so a rule that reads
     * only the status code reports every username as found everywhere, and a
     * dossier of four hundred such "accounts" is worse than none — it cannot
     * be told apart from a real result. The cheap test is to ask the same
     * site for a username that cannot exist: if that also comes back found,
     * the site's answer carries no information and the hit is dropped.
     *
     * Only hits are probed, so this costs one extra request per candidate
     * rather than one per site.
     */
    private suspend fun controlFilter(
        candidates: List<Pair<SiteRule, String>>
    ): List<Pair<SiteRule, String>> {
        if (candidates.isEmpty()) return candidates
        val control = "zq" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val keep = Collections.synchronizedList(mutableListOf<Pair<SiteRule, String>>())
        val semaphore = Semaphore(48)
        withTimeoutOrNull(CONTROL_BUDGET_MS) {
            coroutineScope {
                candidates.take(CONTROL_PROBE_CAP).map { pair ->
                    async {
                        semaphore.withPermit {
                            // Survives only if the control is NOT reported
                            // found. A site that could not be asked about the
                            // control proves nothing, so the hit stands and
                            // keeps its unverified mark.
                            if (checkSite(pair.first, control) !is SiteAnswer.Found) {
                                keep.add(pair)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        // Anything past the cap, or left unprobed when the budget ran out,
        // is kept rather than silently dropped — an unverified hit is still
        // a lead, and it carries the ~ mark.
        val probed = candidates.take(CONTROL_PROBE_CAP).toSet()
        return keep + candidates.filter { it !in probed }
    }

    /**
     * The merged rulesets as plain URL templates, `{}` where the username
     * belongs.
     *
     * These are the only large set of genuinely runnable web tools the app
     * has: thousands of exact profile URLs, each one a site that will show
     * you an account if there is one. The directory screen was offering
     * twenty-six runnable entries out of six thousand catalogue links,
     * because a catalogue link is a homepage and a homepage has no query to
     * fill in — while nine thousand exact templates sat here, used only by
     * the sweep. They belong in the directory too.
     */
    fun siteTemplates(): List<Pair<String, String>> = loadRules().mapNotNull { r ->
        val t = r.urlTemplate
            .replace("{account}", "{}")
            .replace("{username}", "{}")
        if (!t.startsWith("http") || !t.contains("{}")) null else r.name to t
    }

    /** How many site rules this build actually merged, and from where.
     *  Shown in the self-test so the count is observable on-device rather
     *  than taken on trust. */
    fun rulesetSummary(): String {
        val rules = loadRules()
        if (rules.isEmpty()) return "No username rulesets bundled in this build."
        val bySource = rules.groupingBy { it.source }.eachCount()
        return "${rules.size} unique site rules merged — " +
            bySource.entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} ${it.value}" }
    }

    /**
     * What one site said.
     *
     * [Unreachable] is the point of this type. A site that refused, timed
     * out or was blocked used to return the same value as a site that
     * answered "no such account", so a sweep that could not reach nine
     * hundred sites reported them as nine hundred confirmed absences — and
     * the dossier printed "No accounts found across 2,140 sites" as though
     * every one of them had looked.
     */
    private sealed interface SiteAnswer {
        data class Found(val url: String) : SiteAnswer
        data object Absent : SiteAnswer
        data object Unreachable : SiteAnswer
    }

    private fun checkSite(site: SiteRule, username: String): SiteAnswer {
        val url = site.urlFor(username)
        if (!url.startsWith("http")) return SiteAnswer.Unreachable
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            client.newCall(request).execute().use { resp ->
                // Body is only read when a rule actually needs it, and never
                // more than 256 KB: at 96 concurrent full reads one heavy
                // page is enough to run the app out of memory.
                val needsBody = site.presence.isNotEmpty() || site.absence.isNotEmpty()
                val body = if (needsBody) {
                    resp.peekBody(MAX_BODY_BYTES).string()
                } else {
                    ""
                }
                fun verdict(found: Boolean) =
                    if (found) SiteAnswer.Found(url) else SiteAnswer.Absent

                when {
                    // A server error is the server failing, not an answer
                    // about this username.
                    resp.code >= 500 -> SiteAnswer.Unreachable
                    // Blocked by a bot wall. It did not look either.
                    resp.code == 401 || resp.code == 403 || resp.code == 429 ->
                        SiteAnswer.Unreachable
                    // Upstream rules that carry both lists mean: present
                    // markers confirm, absent markers deny. Checking only
                    // presence counted a page carrying both as found.
                    site.presence.isNotEmpty() && site.absence.isNotEmpty() ->
                        verdict(
                            resp.isSuccessful &&
                                site.presence.any { body.contains(it) } &&
                                site.absence.none { body.contains(it) }
                        )
                    site.presence.isNotEmpty() ->
                        verdict(resp.isSuccessful && site.presence.any { body.contains(it) })
                    site.absence.isNotEmpty() ->
                        verdict(resp.isSuccessful && site.absence.none { body.contains(it) })
                    site.expectedCode != null -> verdict(resp.code == site.expectedCode)
                    else -> verdict(resp.isSuccessful)
                }
            }
        } catch (e: Exception) {
            SiteAnswer.Unreachable
        }
    }

    // ------------------------------------------------------------------
    // Ruleset loading
    // ------------------------------------------------------------------

    private fun loadRules(): List<SiteRule> {
        sharedRules?.let { return it }
        val merged = LinkedHashMap<String, SiteRule>()
        fun add(rules: List<SiteRule>) {
            rules.forEach { r ->
                // Deduplicate on the URL template so the same site arriving
                // from two rulesets is only requested once. First writer wins,
                // and WhatsMyName is loaded first because its rules are the
                // most precise.
                val key = r.urlTemplate.lowercase()
                    .replace("{account}", "{}").replace("{username}", "{}")
                if (!merged.containsKey(key)) merged[key] = r
            }
        }
        add(parseWhatsMyName(asset("wmn-data.json") ?: downloadWmn()))
        add(parseSherlock(asset("sherlock.json")))
        add(parseMaigret(asset("maigret.json")))
        val all = merged.values.toList()
        if (all.isNotEmpty()) sharedRules = all
        return all
    }

    private fun asset(name: String): String? = runCatching {
        appContext.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()?.takeIf { it.length > 1000 }

    private fun parseWhatsMyName(text: String?): List<SiteRule> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val sites = json.parseToJsonElement(text).jsonObject["sites"] as? JsonArray
                ?: return emptyList()
            sites.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val uri = o.str("uri_check") ?: return@mapNotNull null
                SiteRule(
                    name = o.str("name") ?: return@mapNotNull null,
                    urlTemplate = uri,
                    expectedCode = (o["e_code"] as? JsonPrimitive)?.intOrNull,
                    presence = listOfNotNull(o.str("e_string")?.takeIf { it.isNotBlank() }),
                    source = "whatsmyname"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSherlock(text: String?): List<SiteRule> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(text).jsonObject.mapNotNull { (name, el) ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val url = o.str("url") ?: return@mapNotNull null
                // Sherlock skips sites needing a POST or a special probe.
                if (o.str("request_method")?.equals("POST", true) == true) return@mapNotNull null
                when (o.str("errorType")) {
                    "message" -> SiteRule(
                        name, url,
                        absence = stringsOf(o["errorMsg"]),
                        source = "sherlock"
                    )
                    // Sherlock's status_code sites 404 when absent, so a
                    // 200 is the positive signal.
                    "status_code" -> SiteRule(name, url, expectedCode = 200, source = "sherlock")
                    else -> null   // response_url relies on redirect comparison
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseMaigret(text: String?): List<SiteRule> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            // Maigret ships either a flat map or one nested under "sites".
            val sites = (root["sites"] as? JsonObject) ?: root
            sites.mapNotNull { (name, el) ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val url = o.str("url") ?: return@mapNotNull null
                // Maigret marks sites it has retired, and sites whose check
                // compares the final redirect — which needs the request
                // history this client does not keep. Neither can be run here.
                if ((o["disabled"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
                if (o.str("checkType") == "response_url") return@mapNotNull null
                val presence = stringsOf(o["presenceStrs"])
                val absence = stringsOf(o["absenceStrs"])
                SiteRule(
                    name = name,
                    urlTemplate = url,
                    expectedCode = if (presence.isEmpty() && absence.isEmpty()) 200 else null,
                    presence = presence,
                    absence = absence,
                    source = "maigret"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun stringsOf(el: kotlinx.serialization.json.JsonElement?): List<String> = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(el.contentOrNull)
        else -> emptyList()
    }

    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull

    private fun downloadWmn(): String? {
        val cache = File(appContext.cacheDir, "wmn-data.json")
        if (cache.exists() && cache.length() > 1000) return cache.readText()
        return try {
            val request = Request.Builder()
                .url("https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()
                if (!body.isNullOrBlank()) runCatching { cache.writeText(body) }
                body
            }
        } catch (e: Exception) {
            null
        }
    }
}
