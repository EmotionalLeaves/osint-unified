package com.osint.unified.domain.executor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import com.osint.unified.domain.orStop
import com.osint.unified.domain.ProfileSignals
import com.osint.unified.network.Http
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

    /**
     * Built from the shared client, not from scratch.
     *
     * A client of its own meant the sweep — the single heaviest thing in
     * the app, two thousand requests in forty seconds — was the one part
     * that ignored the per-host limit, the 429 cooldown and the proxy
     * setting. Somebody turning the proxy on would have sent two thousand
     * requests out of their own address while the dossier went through
     * Tor. The timeouts here are its own, because a sweep cannot wait
     * twenty seconds for any one of two thousand sites.
     */
    private val client: OkHttpClient = Http.client.newBuilder()
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
        /** The code that means the account is not there. WhatsMyName and
         *  Sherlock both publish it and neither was being read, so a site
         *  that answers 200 with "no such user" was counted as a hit
         *  wherever it did not also publish a missing-string. */
        val absentCode: Int? = null,
        val presence: List<String> = emptyList(),
        val absence: List<String> = emptyList(),
        /**
         * A different address to ask, where the public profile page is
         * not the thing that answers. Sherlock publishes these and they
         * were ignored, so those sites were checked against a page that
         * cannot say whether the account exists.
         */
        val probeTemplate: String? = null,
        /**
         * What a username on this site may look like. A site that only
         * allows letters cannot have an account called "j.doe-99", so
         * asking is a request that can only produce a false positive.
         */
        val usernamePattern: String? = null,
        val source: String = ""
    ) {
        /** A rule that only reads the status code cannot tell a profile
         *  from a site that answers 200 to everything. Those hits get a
         *  control probe before they are believed. */
         val statusOnly: Boolean get() = presence.isEmpty() && absence.isEmpty()

        fun urlFor(username: String): String = fill(urlTemplate, username)

        /** What to request, which is not always what to show. */
        fun probeFor(username: String): String = fill(probeTemplate ?: urlTemplate, username)

        /** Whether this site could hold a name of this shape at all. */
        fun accepts(username: String): Boolean {
            val pattern = usernamePattern ?: return true
            // Anchored. The rulesets' patterns are mostly written
            // without anchors — "[A-Za-z0-9_]{3,20}" and the like —
            // and containsMatchIn passes "j.doe-99" on the strength of
            // the "doe" inside it, so the guard never rejected
            // anything it was meant to.
            //
            // It matters in both directions. A name the site cannot
            // hold was still asked about, which is a false positive
            // waiting to happen; and when this does reject, the site
            // is counted as having said no, which feeds the coverage
            // figure behind "every site that was asked said no". Sites
            // that were never asked were inflating it.
            val anchored = when {
                pattern.startsWith("^") || pattern.endsWith("$") -> pattern
                else -> "^(?:$pattern)$"
            }
            // Unicode character classes, because the rulesets were
            // written for Python, where \w matches any letter. Java's
            // \w is [a-zA-Z0-9_] unless this flag is set, so without
            // it every pattern built on \w rejected every accented
            // or non-Latin name — the same failure as the encoding
            // one above, arriving by a different route.
            return runCatching {
                java.util.regex.Pattern
                    .compile(anchored, java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
                    .matcher(username)
                    .matches()
            }.getOrDefault(true)
        }

        private fun fill(template: String, username: String): String = template
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
        // Private: the companion as a whole is visible now, so that the
        // classifier can read the verdict field, but the rule type is an
        // implementation detail and a public property of a private type
        // does not compile.
        @Volatile private var sharedRules: List<SiteRule>? = null

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

        /** 35 + 12 + 15 = 62 s, inside the 70 s the registry allows. */
        const val SWEEP_BUDGET_MS = 35_000L
        const val CONTROL_BUDGET_MS = 12_000L

        /** Reading the profiles that were found, to see which agree. */
        const val VERIFY_BUDGET_MS = 15_000L

        /** How many profiles are read. Beyond this the sweep stops being
         *  a sweep and becomes a crawl. */
        const val VERIFY_CAP = 30
    }

    // Http.bulk rather than Dispatchers.IO: this is the one caller
    // that can occupy the whole shared pool on its own, and the waits
    // the politeness interceptor imposes are blocking.
    suspend fun check(username: String): String = withContext(com.osint.unified.network.Http.bulk) {
        val sites = loadRules()
        if (sites.isEmpty()) {
            // With the verdict field, like every other exit from this
            // function. A prose fallback in the classifier reads the
            // opening words today, which works and should not have to.
            return@withContext "Could not load any site ruleset (check network).\n" +
                "$VERDICT_FIELD$VERDICT_UNREACHABLE"
        }

        // A username with characters a site's URL cannot carry was being
        // injected raw into thousands of templates; sites that reject the
        // format answer with their homepage, which then counted as a hit.
        val encoded = java.net.URLEncoder.encode(username, "UTF-8").replace("+", "%20")

        val found = Collections.synchronizedList(mutableListOf<Pair<SiteRule, String>>())
        val semaphore = Semaphore(64)
        val answeredNo = java.util.concurrent.atomic.AtomicInteger(0)
        val couldNotAsk = java.util.concurrent.atomic.AtomicInteger(0)
        val notApplicable = java.util.concurrent.atomic.AtomicInteger(0)

        // The outer per-tool cap is 70 s. Sweep plus control probes has to
        // finish inside that, or the whole result is thrown away and
        // replaced by a generic timeout just as it completes.
        val completed = withTimeoutOrNull(SWEEP_BUDGET_MS) {
            coroutineScope {
                sites.map { site ->
                    async {
                        semaphore.withPermit {
                            when (val answer = checkSite(site, username, encoded)) {
                                is SiteAnswer.Found -> found.add(site to answer.url)
                                SiteAnswer.Absent -> answeredNo.incrementAndGet()
                                SiteAnswer.Unreachable -> couldNotAsk.incrementAndGet()
                                SiteAnswer.NotApplicable -> notApplicable.incrementAndGet()
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
        // Sites that could never hold this name are out of the
        // denominator as well as the numerator: coverage is of the
        // sites this name could have been on.
        val inapplicable = notApplicable.get()
        val askable = (sites.size - inapplicable).coerceAtLeast(1)
        val notReached = (askable - said - refused).coerceAtLeast(0)

        // Anything matched on page content is believed; anything matched on
        // a bare 200 is not, until a control proves the site can say no.
        val (byContent, byStatus) = raw.partition { !it.first.statusOnly }
        val checked = controlFilter(byStatus)
        val survived = checked.filter { it.control != Control.SAYS_YES_TO_ANYTHING }
        val discarded = byStatus.size - survived.size
        val hits = (byContent.map { Checked(it.first, it.second, Control.NOT_NEEDED) } + survived)
            .sortedBy { it.site.name.lowercase() }
        // Which hits were actually put to a control, as a set the output
        // can consult. The mark used to be appended from site.statusOnly
        // alone — a fact about how the site is tested, not about whether
        // anything tested it — while controlFilter only probes the first
        // CONTROL_PROBE_CAP candidates within CONTROL_BUDGET_MS. So a
        // sweep with more than four hundred status-only hits, or one cut
        // off by the budget, marked unprobed hits identically to probed
        // ones and then told the investigator every mark meant
        // "confirmed by a control username". Sites that say yes to any
        // name were being presented as verified accounts for the person
        // being looked at.

        // The verdict is worked out here, from the counts, and written as
        // a field rather than left to be guessed from the prose. The
        // previous arrangement appended "This is not a clean sheet" to
        // every no-hit result and the classifier matched on that phrase,
        // so a sweep with full coverage and no hits — the definition of an
        // absence — was filed as "could not be reached". The inverse of
        // the bug it replaced, and just as useless.
        // Which of the hits agree with each other.
        //
        // Forty sites saying a name is taken is forty facts about the
        // name. The pages themselves usually say who owns them — the same
        // display name, the same sentence of biography, the same link
        // out, the same avatar — so they are read, and the ones that
        // agree are reported as agreeing. That is the whole difference
        // between a list of possibilities and something to act on.
        val clusters =
            if (hits.isEmpty()) emptyList()
            else verify(hits.map { it.site to it.url }, username)

        val reached = said.toDouble() / askable
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
                append("Coverage: $coverage of $askable sites")
                append(if (completed) "." else " — stopped at the time cap.")
                if (inapplicable > 0) {
                    append("\n$inapplicable more could not hold a name of this shape ")
                    append("and were not asked.")
                }
                if (verdict == VERDICT_ABSENT) {
                    append("\nEvery site that was asked said no.")
                } else {
                    append("\nThis is not a clean sheet: only the $said sites that ")
                    append("answered said no.")
                }
            } else {
                append("Found ${hits.size} accounts for \"$username\".\n")
                append("Coverage: $coverage of $askable sites")
                append(if (completed) ":" else " — stopped at the time cap:")
                append("\n")
                hits.forEach { hit ->
                    append(hit.site.name)
                    append(hit.control.mark)
                    append(" — ")
                    append(hit.url)
                    append("\n")
                }
            }
            if (clusters.isNotEmpty()) {
                append("\nTHESE AGREE WITH EACH OTHER\n")
                append("A name being taken somewhere is a fact about the name. ")
                append("These profiles also carry the same details, which is a ")
                append("fact about a person.\n")
                clusters.take(6).forEach { cluster ->
                    append("\n• ").append(cluster.reason.take(160)).append('\n')
                    cluster.accounts.forEach { account ->
                        append("    ").append(account.site).append(" — ")
                        append(account.url).append('\n')
                        account.signals.displayName?.let {
                            append("      name: ").append(it.take(80)).append('\n')
                        }
                        account.signals.bio?.let {
                            append("      bio: ").append(it.take(140)).append('\n')
                        }
                    }
                }
            }

            if (discarded > 0) {
                append("\n")
                append("$discarded apparent hit")
                append(if (discarded == 1) " was" else "s were")
                append(" dropped: the site returned the same answer for a username ")
                append("that cannot exist, so it says yes to everything.")
            }
            if (hits.any { it.control == Control.SAYS_NO_TO_CONTROL }) {
                append("\n~ = the page was not read; the site answered differently ")
                append("for a username that cannot exist, so its yes means ")
                append("something.")
            }
            val unchecked = hits.count { it.control == Control.NOT_ASKED }
            if (unchecked > 0) {
                append("\n? = the page was not read and no control username was ")
                append("tried ($unchecked of them: the control pass stops at ")
                append("$CONTROL_PROBE_CAP sites or ${CONTROL_BUDGET_MS / 1000} ")
                append("seconds, whichever comes first). A site that says yes to ")
                append("every name would look like this. Treat each as a lead to ")
                append("open, not as an account.")
            }
        }
    }

    /**
     * Reads the profiles that were found and groups the ones that agree.
     *
     * This is the same class of request the sweep already made — the
     * sites were just asked whether the name exists — so it adds no new
     * exposure beyond fetching a page that was already confirmed to be
     * there. It is capped and time-boxed, because a sweep that turns
     * into a crawl stops finishing.
     */
    private suspend fun verify(
        hits: List<Pair<SiteRule, String>>,
        username: String
    ): List<ProfileSignals.Cluster> {
        val accounts = Collections.synchronizedList(mutableListOf<ProfileSignals.Account>())
        val gate = Semaphore(16)
        withTimeoutOrNull(VERIFY_BUDGET_MS) {
            coroutineScope {
                hits.take(VERIFY_CAP).map { (site, url) ->
                    async {
                        gate.withPermit {
                            val html = runCatching { fetchPage(url) }.orStop().getOrNull()
                            if (!html.isNullOrBlank()) {
                                val signals = ProfileSignals.read(
                                    html, ProfileSignals.hostOf(url)
                                )
                                if (!signals.isEmpty) {
                                    accounts.add(
                                        ProfileSignals.Account(site.name, url, signals)
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return ProfileSignals.cluster(accounts.toList(), searchedFor = username)
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
    ): List<Checked> {
        if (candidates.isEmpty()) return emptyList()
        val control = "zq" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val semaphore = Semaphore(48)
        // A verdict per candidate, by position: 0 never asked, 1 the
        // control came back found, 2 the control came back absent.
        //
        // The old version collected survivors in a list and then kept
        // "anything not in the first CONTROL_PROBE_CAP". Everything
        // inside the cap was in that set whether or not the twelve
        // second budget had reached it — so on a slow connection the
        // candidates the timeout cut off were in neither list and
        // vanished, which is the opposite of what the comment under
        // it promised. Worse, they were then counted as discarded and
        // the output accused those sites of answering yes to
        // everything, which nobody had asked them.
        val verdict = java.util.concurrent.atomic.AtomicIntegerArray(candidates.size)
        withTimeoutOrNull(CONTROL_BUDGET_MS) {
            coroutineScope {
                candidates.take(CONTROL_PROBE_CAP).forEachIndexed { index, pair ->
                    launch {
                        semaphore.withPermit {
                            // Survives only if the control is NOT reported
                            // found. A site that could not be asked about the
                            // control proves nothing, so the hit stands and
                            // keeps its unverified mark.
                            val soft =
                                checkSite(pair.first, control, control) is SiteAnswer.Found
                            verdict.set(index, if (soft) 1 else 2)
                        }
                    }
                }
            }
        }
        // Dropped only where the control actually came back found.
        // Past the cap, cut off by the budget, or unreachable: kept,
        // because an unverified hit is still a lead and it carries the
        // ~ mark.
        return candidates.mapIndexed { index, pair ->
            Checked(
                pair.first,
                pair.second,
                when (verdict.get(index)) {
                    1 -> Control.SAYS_YES_TO_ANYTHING
                    2 -> Control.SAYS_NO_TO_CONTROL
                    else -> Control.NOT_ASKED
                }
            )
        }
    }

    /** A hit, and what if anything was done to check it. */
    private data class Checked(val site: SiteRule, val url: String, val control: Control)

    /** How much a hit's yes is worth. */
    private enum class Control(val mark: String) {
        /** The page itself said so, so no control was needed. */
        NOT_NEEDED(""),

        /** Status only, and the control username came back absent. */
        SAYS_NO_TO_CONTROL(" ~"),

        /** Status only, and nothing was tried: past the cap or the budget. */
        NOT_ASKED(" ?"),

        /** Status only, and the control came back found. Dropped. */
        SAYS_YES_TO_ANYTHING(" !"),
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

        /**
         * This site cannot hold a name of this shape, so it was never
         * asked.
         *
         * It used to answer Absent, which is a site saying no — and
         * "no" counts towards coverage. For a handle with a dot or a
         * hyphen in it a large share of the rulesets reject the shape,
         * so coverage cleared the eighty-percent bar on rejections
         * alone and the sweep concluded "Every site that was asked
         * said no", having asked almost none of them.
         */
        data object NotApplicable : SiteAnswer
    }

    /** The page itself, capped, for reading its profile metadata. */
    private fun fetchPage(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.peekBody(MAX_BODY_BYTES).string()
        }
    }

    /**
     * [raw] is the name as typed; [encoded] is it fitted into a URL.
     *
     * The ruleset's pattern describes a NAME, and it was being tested
     * against the URL-encoded form. "josé" encodes to "jos%C3%A9",
     * and the patterns these rulesets ship are overwhelmingly of the
     * shape ^[\w.+-]+$ — which the percent signs fail. So every
     * non-ASCII handle was NotApplicable at every site, and because
     * inapplicable sites are out of the coverage denominator, the
     * sweep then reported near-total coverage of a list it had
     * barely asked. The encoded form is for building the URL and
     * nothing else.
     */
    private fun checkSite(site: SiteRule, raw: String, encoded: String): SiteAnswer {
        val url = site.urlFor(encoded)
        val probe = site.probeFor(encoded)
        if (!url.startsWith("http") || !probe.startsWith("http")) return SiteAnswer.Unreachable
        // A name this site could not hold is not a question worth asking,
        // and asking it can only produce a false positive.
        if (!site.accepts(raw)) return SiteAnswer.NotApplicable
        return try {
            val request = Request.Builder()
                .url(probe)
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
                    // The code the ruleset says means "no such account",
                    // which settles it before anything else is weighed.
                    site.absentCode != null && resp.code == site.absentCode ->
                        SiteAnswer.Absent
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
                    // WhatsMyName publishes both halves of the test: the
                    // code and string that mean present, and the code and
                    // string that mean missing. Only the first half was
                    // being read, so a site answering 200 with "that user
                    // does not exist" was a hit.
                    absentCode = (o["m_code"] as? JsonPrimitive)?.intOrNull
                        ?.takeIf { it != (o["e_code"] as? JsonPrimitive)?.intOrNull },
                    presence = listOfNotNull(o.str("e_string")?.takeIf { it.isNotBlank() }),
                    absence = listOfNotNull(o.str("m_string")?.takeIf { it.isNotBlank() }),
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
                // Sherlock gives some sites a different address to ask
                // than the one to show, and a pattern the username must
                // fit. Both were ignored: the first meant those sites
                // were checked against a page that cannot answer, the
                // second meant asking sites about names they could not
                // possibly hold.
                val probe = o.str("urlProbe")
                val pattern = o.str("regexCheck")
                // The code meaning "absent" where it is published, rather
                // than assuming 404.
                val absentCode = (o["errorCode"] as? JsonPrimitive)?.intOrNull
                    ?: (o["errorCode"] as? JsonArray)
                        ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.intOrNull }
                when (o.str("errorType")) {
                    "message" -> SiteRule(
                        name, url,
                        absentCode = absentCode,
                        absence = stringsOf(o["errorMsg"]),
                        probeTemplate = probe,
                        usernamePattern = pattern,
                        source = "sherlock"
                    )
                    // Sherlock's status_code sites 404 when absent, so a
                    // 200 is the positive signal — unless the ruleset
                    // names a different code, which it sometimes does.
                    "status_code" -> SiteRule(
                        name, url,
                        expectedCode = 200,
                        absentCode = absentCode ?: 404,
                        probeTemplate = probe,
                        usernamePattern = pattern,
                        source = "sherlock"
                    )
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
