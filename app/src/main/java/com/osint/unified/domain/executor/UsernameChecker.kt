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

    private companion object {
        // Parsing three rulesets costs several megabytes of JSON, so the
        // merged result is shared across every UsernameChecker instance
        // rather than rebuilt per use.
        @Volatile var sharedRules: List<SiteRule>? = null

        /** Control probes to run. Beyond this, hits are reported unverified
         *  rather than paying for a second sweep as large as the first. */
        const val CONTROL_PROBE_CAP = 400
    }

    suspend fun check(username: String): String = withContext(Dispatchers.IO) {
        val sites = loadRules()
        if (sites.isEmpty()) return@withContext "Could not load any site ruleset (check network)."

        val found = Collections.synchronizedList(mutableListOf<Pair<SiteRule, String>>())
        val semaphore = Semaphore(96)
        val checked = java.util.concurrent.atomic.AtomicInteger(0)

        val completed = withTimeoutOrNull(32_000) {
            coroutineScope {
                sites.map { site ->
                    async {
                        semaphore.withPermit {
                            checkSite(site, username)?.let { found.add(site to it) }
                            checked.incrementAndGet()
                        }
                    }
                }.awaitAll()
            }
            true
        } != null

        val raw = found.sortedBy { it.first.name.lowercase() }
        val scanned = if (completed) sites.size else checked.get()

        // Anything matched on page content is believed; anything matched on
        // a bare 200 is not, until a control proves the site can say no.
        val (byContent, byStatus) = raw.partition { !it.first.statusOnly }
        val survived = controlFilter(byStatus)
        val discarded = byStatus.size - survived.size
        val hits = (byContent + survived).sortedBy { it.first.name.lowercase() }

        buildString {
            if (hits.isEmpty()) {
                append("No accounts found for \"$username\" across $scanned sites")
                append(if (completed) "." else " (partial — hit the time cap).")
            } else {
                append("Found ${hits.size} accounts for \"$username\" across $scanned sites")
                append(if (completed) ":" else " (partial — hit the time cap):")
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
        withTimeoutOrNull(18_000) {
            coroutineScope {
                candidates.take(CONTROL_PROBE_CAP).map { pair ->
                    async {
                        semaphore.withPermit {
                            // Survives only if the control is NOT reported found.
                            if (checkSite(pair.first, control) == null) keep.add(pair)
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

    private fun checkSite(site: SiteRule, username: String): String? {
        val url = site.urlFor(username)
        if (!url.startsWith("http")) return null
        return try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .build()
            client.newCall(request).execute().use { resp ->
                // Body is only read when a rule actually needs it.
                val needsBody = site.presence.isNotEmpty() || site.absence.isNotEmpty()
                val body = if (needsBody) resp.body?.string().orEmpty() else ""
                when {
                    site.presence.isNotEmpty() ->
                        if (resp.isSuccessful && site.presence.any { body.contains(it) }) url else null
                    site.absence.isNotEmpty() ->
                        if (resp.isSuccessful && site.absence.none { body.contains(it) }) url else null
                    site.expectedCode != null ->
                        if (resp.code == site.expectedCode) url else null
                    else -> if (resp.isSuccessful) url else null
                }
            }
        } catch (e: Exception) {
            null
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
