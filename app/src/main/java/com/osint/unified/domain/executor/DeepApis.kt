package com.osint.unified.domain.executor

import com.osint.unified.data.Freshness
import com.osint.unified.domain.Hostnames
import com.osint.unified.domain.andMore
import com.osint.unified.network.Http
import com.osint.unified.network.Http.bodyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The second wave of keyless OSINT sources — identity pivots (Keybase,
 * Wikidata, Reddit, Hacker News, GitHub activity), breach data, corporate and
 * sanctions records, aircraft and wallet lookups, crime data and
 * chronolocation maths.
 *
 * Every endpoint here was probed live from CI before being wired in. None of
 * them take an API key and none require an account.
 */
class DeepApis(
    /** Where the SEC contact address comes from; empty means EDGAR is off. */
    private val secContactEmail: () -> String = { "" },
    private val client: OkHttpClient = Http.client
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /**
     * What this app calls itself, which is deliberately not itself.
     *
     * The network layer sets a plain mobile-browser agent on every
     * request for a reason it states at length: several of these calls go to
     * hosts the TARGET controls — reading their security.txt,
     * fetching their certificate, crawling their page — and a user
     * agent announcing an OSINT tool writes "somebody is
     * investigating you" into their access log. This class overrode
     * that for every request it made, which is the policy exactly
     * inverted.
     *
     * [RESEARCH_UA] remains for the services whose terms ask to be
     * told who is calling; SEC EDGAR builds its own, from the
     * investigator's address, in SecAgent.
     */
    private val ua = com.osint.unified.network.Http.defaultUserAgent

    /**
     * For a service whose terms ask to be told who is calling.
     *
     * OpenStreetMap's Nominatim and Overpass both require an agent
     * that identifies the application, so for those the honest
     * string is also the compliant one — and none of them is a host
     * the target controls, which is the only reason the browser
     * string exists.
     */
    private val researchUa = "OSINT-Unified/1.0 " +
        "(+https://github.com/EmotionalLeaves/osint-unified)"

    /** Hosts that ask to be told, by their own usage policy. */
    private fun agentFor(url: String, fallback: String): String {
        val host = runCatching {
            url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        }.getOrDefault("")
        val asks = host.endsWith("openstreetmap.org") ||
            host.endsWith("overpass-api.de") ||
            host.endsWith("openstreetmap.fr") ||
            host.endsWith("kartaview.org") ||
            host.endsWith("panoramax.xyz") ||
            host.endsWith("openstreetcam.org")
        return if (asks) researchUa else fallback
    }
    private val browserUa =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // ------------------------------------------------------------------
    // Identity pivots
    // ------------------------------------------------------------------

    /** Keybase: cryptographically proven links between a username and their
     *  Twitter/GitHub/Reddit/HN accounts, websites and PGP key. */
    fun keybase(username: String): Result<String> = runCatching {
        val u = URLEncoder.encode(username, "UTF-8")
        val body = get("https://keybase.io/_/api/1.0/user/lookup.json?usernames=$u&fields=basics%2Cproofs_summary")
        val them = json.parseToJsonElement(body).jsonObject["them"] as? JsonArray
        val who = them?.firstOrNull() as? JsonObject
            ?: return@runCatching "Keybase: no account named '$username'."
        val sb = StringBuilder("Keybase — proven identities for '$username':\n")
        val groups = (who["proofs_summary"] as? JsonObject)?.get("by_presentation_group") as? JsonObject
        var found = 0
        groups?.forEach { (service, arr) ->
            (arr as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val tag = o.str("nametag") ?: return@forEach
                val link = o.str("service_url") ?: o.str("human_url").orEmpty()
                sb.append("• ${service.uppercase()}: $tag").append(if (link.isNotBlank()) "\n  $link\n" else "\n")
                found++
            }
        }
        if (found == 0) sb.append("Account exists but publishes no proofs.\n")
        sb.append("\nProfile: https://keybase.io/${seg(username)}")
        sb.toString()
    }

    /** Reddit public profile plus recent comment/post subreddits. Reddit blocks
     *  datacenter ranges, so this is best-effort from a phone. */
    fun reddit(username: String): Result<String> = runCatching {
        val u = URLEncoder.encode(username, "UTF-8")
        val about = runCatching {
            json.parseToJsonElement(get("https://www.reddit.com/user/$u/about.json", browserUa))
                .jsonObject["data"]?.jsonObject
        }.getOrNull() ?: return@runCatching "Reddit: no public data for u/$username (or Reddit blocked the request).\nhttps://www.reddit.com/user/${seg(username)}"
        val sb = StringBuilder("Reddit — u/$username\n")
        about.int("total_karma")?.let { sb.append("Karma: $it (post ${about.int("link_karma") ?: 0} / comment ${about.int("comment_karma") ?: 0})\n") }
        about.dbl("created_utc")?.let { sb.append("Created: ${utc(it.toLong() * 1000)}\n") }
        (about["subreddit"] as? JsonObject)?.let { s ->
            s.str("public_description")?.takeIf { it.isNotBlank() }?.let { sb.append("Bio: $it\n") }
        }
        if (about["verified"]?.toString() == "true") sb.append("Email verified: yes\n")
        if (about["is_mod"]?.toString() == "true") sb.append("Moderator: yes\n")

        // Recent activity reveals interests, timezone and often location.
        runCatching {
            val arr = json.parseToJsonElement(get("https://www.reddit.com/user/$u/.json?limit=50", browserUa))
                .jsonObject["data"]?.jsonObject?.get("children") as? JsonArray
            val subs = LinkedHashMap<String, Int>()
            val hours = IntArray(24)
            arr?.forEach { el ->
                val d = (el as? JsonObject)?.get("data") as? JsonObject ?: return@forEach
                d.str("subreddit")?.let { subs[it] = (subs[it] ?: 0) + 1 }
                d.dbl("created_utc")?.let { hours[hourOf(it.toLong() * 1000)]++ }
            }
            if (subs.isNotEmpty()) {
                sb.append("\nActive in (last 50): ")
                sb.append(subs.entries.sortedByDescending { it.value }.take(12)
                    .joinToString(", ") { "r/${it.key} (${it.value})" })
                sb.append("\n")
                val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
                val quiet = sleepWindow(hours)
                sb.append("Posting peak: ${peak}:00 UTC; quiet ${quiet}. ")
                sb.append("Quiet hours usually bracket local night — a rough timezone hint.\n")
            }
        }
        sb.append("\nhttps://www.reddit.com/user/${seg(username)}")
        sb.toString()
    }

    /** Hacker News account: karma, age, about text (often holds contact info). */
    fun hackerNews(username: String): Result<String> = runCatching {
        val u = URLEncoder.encode(username, "UTF-8")
        val o = json.parseToJsonElement(get("https://hacker-news.firebaseio.com/v0/user/$u.json")) as? JsonObject
            ?: return@runCatching "Hacker News: no account named '$username'."
        val sb = StringBuilder("Hacker News — $username\n")
        o.int("karma")?.let { sb.append("Karma: $it\n") }
        o.dbl("created")?.let { sb.append("Created: ${utc(it.toLong() * 1000)}\n") }
        o.str("about")?.takeIf { it.isNotBlank() }?.let {
            sb.append("About: ${stripTags(it)}\n")
            emailsIn(it).takeIf { e -> e.isNotEmpty() }?.let { e -> sb.append("Emails in bio: ${e.joinToString(", ")}\n") }
        }
        (o["submitted"] as? JsonArray)?.let { sb.append("Submissions: ${it.size}\n") }
        sb.append("https://news.ycombinator.com/user?id=${seg(username)}")
        sb.toString()
    }

    /** GitHub activity mining: public events expose commit author emails, the
     *  repos and orgs someone touches, and their working hours. */
    fun githubActivity(username: String): Result<String> = runCatching {
        val u = URLEncoder.encode(username, "UTF-8")
        val sb = StringBuilder("GitHub activity — $username\n")
        val emails = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        val repos = LinkedHashMap<String, Int>()
        val hours = IntArray(24)
        // Kept apart from an empty array.
        //
        // Unauthenticated GitHub is sixty requests an hour and this
        // class makes several per dossier, so a 403 is routine; a 404
        // means no such user; a dropped connection means nothing at
        // all. All three used to arrive here as `null` and come out
        // the far end as "No public activity in the last 90 days",
        // which is a statement about a person's year made from a
        // request that was refused.
        val attempt = runCatching {
            json.parseToJsonElement(get("https://api.github.com/users/$u/events/public?per_page=100")).jsonArray
        }
        attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val reached = attempt.isSuccess
        val events = attempt.getOrNull()
        events?.forEach { el ->
            val e = el as? JsonObject ?: return@forEach
            ((e["repo"] as? JsonObject)?.str("name"))?.let { repos[it] = (repos[it] ?: 0) + 1 }
            e.str("created_at")?.let { ts -> isoHour(ts)?.let { hours[it]++ } }
            val commits = (e["payload"] as? JsonObject)?.get("commits") as? JsonArray
            commits?.forEach { c ->
                val author = (c as? JsonObject)?.get("author") as? JsonObject
                author?.str("email")?.let { if (!it.endsWith("users.noreply.github.com")) emails.add(it) }
                author?.str("name")?.let { names.add(it) }
            }
        }
        if (emails.isNotEmpty()) sb.append("Commit emails: ${emails.joinToString(", ")}\n")
        if (names.isNotEmpty()) sb.append("Commit author names: ${names.joinToString(", ")}\n")
        if (repos.isNotEmpty()) {
            sb.append("Recent repos: ")
            sb.append(repos.entries.sortedByDescending { it.value }.take(10).joinToString(", ") { it.key })
            sb.append("\n")
        }
        if (hours.any { it > 0 }) {
            val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
            sb.append("Commit peak: ${peak}:00 UTC, quiet ${sleepWindow(hours)} (timezone hint)\n")
        }
        runCatching {
            val gists = json.parseToJsonElement(get("https://api.github.com/users/$u/gists?per_page=10")).jsonArray
            if (gists.isNotEmpty()) {
                sb.append("\nGists (${gists.size}):\n")
                gists.take(10).forEach { g ->
                    val o = g as? JsonObject ?: return@forEach
                    sb.append("• ${o.str("description")?.take(70) ?: "(no description)"}\n  ${o.str("html_url")}\n")
                }
                sb.andMore(gists.size, 10)
            }
        }
        if (emails.isEmpty() && repos.isEmpty()) {
            // Verdict first, because the classifier reads the first
            // non-blank line and the header above it is not one.
            val why = if (reached) {
                "No public activity for $username in GitHub's last 300 events."
            } else {
                val code = httpCode(attempt.exceptionOrNull() ?: IOException(""))
                when (code) {
                    404 -> "No GitHub account under the name $username."
                    403, 429 ->
                        "GitHub could not be reached for $username — rate limited " +
                            "(the public API allows sixty requests an hour)."
                    else -> "GitHub could not be reached for $username."
                }
            }
            return@runCatching buildString {
                append(why).append('\n')
                // What the event feed can and cannot tell you, said
                // once rather than implied: it is capped at 300
                // events or ninety days, whichever comes first, so a
                // quiet feed is not the same as a quiet year.
                if (reached) {
                    append("The public event feed holds at most 300 events or ")
                    append("ninety days, so this is not a statement about the ")
                    append("whole account.\n")
                }
                append("\nhttps://github.com/${seg(username)}")
            }
        }
        sb.append("\nhttps://github.com/${seg(username)}")
        sb.toString()
    }

    /** Wikidata: structured facts and linked social handles for a person,
     *  company or place. Resolves entity-valued claims to readable labels. */
    fun wikidata(query: String): Result<String> = runCatching {
        val q = URLEncoder.encode(query, "UTF-8")
        // Through MediaWiki: a 200 carrying an `error` object and no
        // `search` key is a refusal, and it was being printed as
        // "Wikidata: nothing matching '<name>'" — an absence this
        // source never asserted, which Findings then counted as a
        // source that looked.
        val hits = MediaWiki.body(
            get(
                "https://www.wikidata.org/w/api.php?action=wbsearchentities" +
                    "&search=$q&language=en&format=json&limit=3"
            ),
            "Wikidata search"
        )["search"] as? JsonArray
        val first = hits?.firstOrNull() as? JsonObject
            ?: return@runCatching "Wikidata: nothing matching '$query'."
        val id = first.str("id") ?: return@runCatching "Wikidata: nothing matching '$query'."
        val label = first.str("label") ?: query
        val desc = ((first["display"] as? JsonObject)?.get("description") as? JsonObject)?.str("value")
            ?: first.str("description")

        val claims = (json.parseToJsonElement(
            get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$id&props=claims&format=json")
        ).jsonObject["entities"] as? JsonObject)?.get(id)?.jsonObject?.get("claims") as? JsonObject

        val sb = StringBuilder("Wikidata — $label ($id)\n")
        if (!desc.isNullOrBlank()) sb.append("$desc\n")

        // Identifier properties carry straight to a profile URL,
        // except the one that does not.
        val handles = listOf(
            "P2002" to ("X/Twitter" to "https://twitter.com/"),
            "P2037" to ("GitHub" to "https://github.com/"),
            "P2003" to ("Instagram" to "https://instagram.com/"),
            "P2013" to ("Facebook" to "https://facebook.com/"),
            "P3185" to ("VK" to "https://vk.com/"),
            // Handled below, not here: a Mastodon address is
            // user@instance, and gluing https:// to the front of
            // that makes https://user@instance — a URL whose host
            // is the instance and whose username is treated as
            // credentials. It opens the instance's front page, or
            // a browser warning, and never the profile.
            "P4033" to ("Mastodon" to MASTODON),
            "P2397" to ("YouTube channel" to "https://youtube.com/channel/"),
            "P7085" to ("TikTok" to "https://tiktok.com/@"),
            "P6634" to ("LinkedIn" to "https://linkedin.com/in/"),
            "P3417" to ("Quora" to "https://quora.com/profile/"),
            "P4175" to ("Patreon" to "https://patreon.com/"),
            "P2847" to ("Google+" to ""),
            "P856" to ("Website" to "")
        )
        val socialLines = ArrayList<String>()
        handles.forEach { (prop, pair) ->
            claims.stringValues(prop).forEach { v ->
                val url = when {
                    pair.second == MASTODON -> {
                        val at = v.trim().removePrefix("@")
                        val user = at.substringBefore('@')
                        val instance = at.substringAfter('@', "")
                        if (instance.isNotBlank()) "https://$instance/@$user" else v
                    }
                    pair.second.isEmpty() -> v
                    else -> pair.second + v
                }
                socialLines.add("• ${pair.first}: $url")
            }
        }

        // Entity-valued facts need a second call to turn Q-ids into labels.
        val entityProps = linkedMapOf(
            "P31" to "Instance of", "P106" to "Occupation", "P108" to "Employer",
            "P27" to "Citizenship", "P19" to "Birthplace", "P69" to "Educated at",
            "P39" to "Position held", "P102" to "Party", "P17" to "Country",
            "P159" to "HQ location", "P112" to "Founded by", "P488" to "Chairperson",
            "P169" to "CEO", "P127" to "Owned by", "P749" to "Parent org"
        )
        val wanted = LinkedHashMap<String, MutableList<String>>()
        val ids = LinkedHashSet<String>()
        entityProps.keys.forEach { prop ->
            val qs = claims.entityIds(prop)
            if (qs.isNotEmpty()) { wanted[prop] = qs.toMutableList(); ids.addAll(qs) }
        }
        val labels = HashMap<String, String>()
        ids.chunked(45).forEach { chunk ->
            runCatching {
                val ent = json.parseToJsonElement(
                    get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=" +
                        chunk.joinToString("%7C") + "&props=labels&languages=en&format=json")
                ).jsonObject["entities"] as? JsonObject
                ent?.forEach { (k, v) ->
                    ((v as? JsonObject)?.get("labels") as? JsonObject)
                        ?.get("en")?.jsonObject?.str("value")?.let { labels[k] = it }
                }
            }
        }
        // Time and quantity facts read straight off the claim.
        timeValue(claims, "P569")?.let { sb.append("Born: $it\n") }
        timeValue(claims, "P570")?.let { sb.append("Died: $it\n") }
        timeValue(claims, "P571")?.let { sb.append("Founded: $it\n") }
        wanted.forEach { (prop, qs) ->
            val vals = qs.mapNotNull { labels[it] }
            if (vals.isNotEmpty()) sb.append("${entityProps[prop]}: ${vals.joinToString(", ")}\n")
        }
        if (socialLines.isNotEmpty()) {
            sb.append("\nLinked accounts:\n")
            socialLines.forEach { sb.append(it).append("\n") }
        }
        sb.append("\nhttps://www.wikidata.org/wiki/$id")
        if (hits.size > 1) {
            sb.append("\n\nOther matches: ")
            sb.append(hits.drop(1).mapNotNull { (it as? JsonObject)?.str("label") }.joinToString(", "))
        }
        sb.toString()
    }

    /** Telegram public channel preview — subscriber count and recent posts. */
    fun telegram(handle: String): Result<String> = runCatching {
        val name = handle.trim().removePrefix("@").substringAfterLast("/")
        // The fetch is encoded, like the link printed at the end of
        // this function already was.
        val html = get("https://t.me/s/${seg(name)}", browserUa)
        // Only the channel marker counts.
        //
        // t.me serves a generic preview page for a username that does
        // not exist and for an ordinary user account, and that page
        // carries the tgme_page classes — so the second half of this
        // test passed for everything, and the tool asserted a public
        // channel for any string typed into it. tgme_channel_info is
        // the one marker that only a channel has.
        if (!html.contains("tgme_channel_info")) {
            return@runCatching "Telegram: no public channel '@$name' " +
                "(it may be private, or a user account rather than a channel)."
        }
        val sb = StringBuilder("Telegram — @$name\n")
        Regex("""<div class="tgme_channel_info_header_title"[^>]*>(?:<span[^>]*>)?([^<]+)""")
            .find(html)?.groupValues?.get(1)?.let { sb.append("Title: ${it.trim()}\n") }
        Regex("""<div class="tgme_channel_info_counter"><span class="counter_value">([^<]+)</span><span class="counter_type">([^<]+)""")
            .findAll(html).forEach { sb.append("${it.groupValues[2].replaceFirstChar(Char::uppercase)}: ${it.groupValues[1]}\n") }
        Regex("""<div class="tgme_channel_info_description"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { sb.append("Description: ${stripTags(it).trim()}\n") }
        val posts = Regex("""<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { stripTags(it.groupValues[1]).trim() }.filter { it.isNotBlank() }.toList()
        if (posts.isNotEmpty()) {
            sb.append("\nRecent posts (${posts.size}):\n")
            posts.takeLast(10).reversed().forEach { sb.append("• ${it.take(220)}\n") }
        }
        sb.append("\nhttps://t.me/${seg(name)}")
        sb.toString()
    }

    /**
     * One username, checked in parallel against eight platforms that answer
     * without a key or an account: Bluesky, Mastodon, GitLab, Docker Hub,
     * Stack Overflow, Bitbucket, npm and Wikipedia.
     *
     * Each hit carries the detail that matters for identity work — display
     * name, bio, join date, linked site, publishing email where the platform
     * exposes one.
     */
    suspend fun socialSweep(username: String): String = coroutineScope {
        val u = username.trim().removePrefix("@")
        val e = URLEncoder.encode(u, "UTF-8")

        val checks = listOf<Pair<String, suspend () -> String?>>(
            "Bluesky" to {
                // Handles are either name.bsky.social or a custom domain, so
                // try the default form first and the bare handle after.
                val body = runCatching {
                    get("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=$e.bsky.social")
                }.getOrElse {
                    get("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=$e")
                }
                val o = json.parseToJsonElement(body).jsonObject
                o.str("handle")?.let { h ->
                    buildString {
                        append("@$h")
                        o.str("displayName")?.let { append(" — $it") }
                        o.int("followersCount")?.let { append("\n  ${it} followers, ${o.int("followsCount") ?: 0} following, ${o.int("postsCount") ?: 0} posts") }
                        o.str("description")?.takeIf { it.isNotBlank() }?.let { append("\n  ${it.replace("\n", " ").take(200)}") }
                        o.str("createdAt")?.let { append("\n  Joined ${it.substringBefore('T')}") }
                        append("\n  https://bsky.app/profile/${seg(h)}")
                    }
                }
            },
            "Mastodon" to {
                val o = json.parseToJsonElement(
                    get("https://mastodon.social/api/v1/accounts/lookup?acct=$e")
                ).jsonObject
                o.str("username")?.let {
                    buildString {
                        append("@${o.str("acct")}")
                        o.str("display_name")?.takeIf { d -> d.isNotBlank() }?.let { d -> append(" — $d") }
                        append("\n  ${o.int("followers_count") ?: 0} followers, ${o.int("statuses_count") ?: 0} posts")
                        o.str("note")?.takeIf { n -> n.isNotBlank() }?.let { n -> append("\n  ${stripTags(n).take(200)}") }
                        o.str("created_at")?.let { c -> append("\n  Joined ${c.substringBefore('T')}") }
                        append("\n  ${o.str("url")}")
                    }
                }
            },
            "GitLab" to {
                val arr = json.parseToJsonElement(get("https://gitlab.com/api/v4/users?username=$e")) as? JsonArray
                (arr?.firstOrNull() as? JsonObject)?.let { o ->
                    buildString {
                        append(o.str("name") ?: u)
                        o.str("state")?.let { append(" [$it]") }
                        append("\n  ${o.str("web_url")}")
                    }
                }
            },
            "Docker Hub" to {
                val o = json.parseToJsonElement(get("https://hub.docker.com/v2/users/$e/")).jsonObject
                o.str("username")?.let {
                    buildString {
                        append(o.str("full_name")?.takeIf { f -> f.isNotBlank() } ?: u)
                        o.str("company")?.takeIf { c -> c.isNotBlank() }?.let { c -> append(" · $c") }
                        o.str("location")?.takeIf { l -> l.isNotBlank() }?.let { l -> append(" · $l") }
                        o.str("date_joined")?.let { d -> append("\n  Joined ${d.substringBefore('T')}") }
                        o.str("profile_url")?.takeIf { p -> p.isNotBlank() }?.let { p -> append("\n  Linked: $p") }
                        append("\n  https://hub.docker.com/u/${seg(u)}")
                    }
                }
            },
            "Stack Overflow" to {
                // `inname` is a fuzzy search over display names, not a
                // handle lookup — every other probe here is exact. So
                // searching "mike" returned the first three Stack
                // Overflow users with "mike" anywhere in their name,
                // printed their reputation, location and profile
                // link, and counted them as an account belonging to
                // the person being investigated. Only an exact
                // display name counts now, and even that is marked
                // for what it is.
                val items = json.parseToJsonElement(
                    get("https://api.stackexchange.com/2.3/users?inname=$e&site=stackoverflow&pagesize=10")
                ).jsonObject["items"] as? JsonArray
                items?.mapNotNull { it as? JsonObject }
                    ?.filter { it.str("display_name").equals(u, ignoreCase = true) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(3)
                    ?.joinToString("\n") { o ->
                        "  ${o.str("display_name")} — ${o.int("reputation") ?: 0} rep, " +
                            "${o.str("location") ?: "no location"}\n  ${o.str("link")}" +
                            "\n  (display name, which anyone may choose)"
                    }
            },
            "Bitbucket" to {
                val o = json.parseToJsonElement(get("https://api.bitbucket.org/2.0/users/$e")).jsonObject
                o.str("display_name")?.let {
                    "$it\n  https://bitbucket.org/${seg(u)}"
                }
            },
            "npm" to {
                val objs = json.parseToJsonElement(
                    get("https://registry.npmjs.org/-/v1/search?text=author:$e&size=10")
                ).jsonObject["objects"] as? JsonArray
                objs?.takeIf { it.isNotEmpty() }?.let { arr ->
                    val emails = LinkedHashSet<String>()
                    val names = arr.mapNotNull { el ->
                        val pkg = (el as? JsonObject)?.get("package") as? JsonObject
                        ((pkg?.get("publisher") as? JsonObject)).str("email")?.let { emails.add(it) }
                        pkg.str("name")
                    }
                    buildString {
                        // "10 packages" for anyone prolific, because
                        // the request asks for ten. Said as what it is.
                        val more = if (arr.size >= 10) "at least " else ""
                        append("  $more${arr.size} packages: ${names.take(10).joinToString(", ")}")
                        if (emails.isNotEmpty()) append("\n  Publishing email: ${emails.joinToString(", ")}")
                    }
                }
            },
            "Wikipedia" to {
                // A refusal here read as "no such account", which
                // is precisely what the "Three outcomes, not two"
                // comment below this block exists to prevent.
                val contribs = MediaWiki.query(
                    get(
                        "https://en.wikipedia.org/w/api.php?action=query" +
                            "&list=usercontribs&ucuser=$e&format=json&uclimit=20"
                    ),
                    "Wikipedia contributions"
                )["usercontribs"] as? JsonArray
                contribs?.takeIf { it.isNotEmpty() }?.let { arr ->
                    val pages = arr.mapNotNull { (it as? JsonObject).str("title") }.distinct()
                    "  ${arr.size} recent edits\n  Pages: ${pages.take(8).joinToString(", ")}" +
                        "\n  https://en.wikipedia.org/wiki/Special:Contributions/${seg(u)}"
                }
            }
        )

        // Three outcomes, not two.
        //
        // This collapsed "the platform answered and there is no such
        // account" into the same bucket as "the request threw" — a
        // 429, a 5xx, a DNS failure, a dead proxy. So a sweep run on a
        // flaky connection printed "0 of 8 platforms have an account
        // under this name. No account found on: …" and listed every
        // platform it had failed to reach, as a finding, in a dossier
        // about a real person. The username checker alongside this one
        // has had a three-state answer for exactly this reason.
        // A platform's own "no such user" is an answer.
        //
        // get() throws on every non-2xx, and four of these endpoints
        // report an unknown handle with a status rather than an empty
        // body: Bluesky 400, Mastodon 404, Docker Hub 404, Bitbucket
        // 404. So the three-state split above, which exists to stop a
        // failure being reported as an absence, was doing the inverse
        // for half the list — those four could never produce an
        // absence at all, the denominator on the second line was
        // permanently wrong, and a handle that genuinely exists
        // nowhere came back as four platforms "could not be reached".
        //
        // Only the statuses a platform uses to mean "not here" count.
        // A 403, a 429 and anything 5xx stay failures, which is what
        // they are.
        val results = checks.map { (name, probe) ->
            async {
                name to runCatching { probe() }.recoverCatching { e ->
                    val code = httpCode(e)
                    if (code != null && code in (SAYS_NO[name] ?: SAYS_NO_DEFAULT)) null
                    else throw e
                }
            }
        }.map { it.await() }

        val answered = results.filter { it.second.isSuccess }
        val unreachable = results.filter { it.second.isFailure }.map { it.first }
        val hits = answered.filter { !it.second.getOrNull().isNullOrBlank() }
        val misses = answered
            .filter { it.second.getOrNull().isNullOrBlank() }
            .map { it.first }

        buildString {
            // The verdict is a field, not a sentence.
            //
            // Executors.classify routes socialsweep into the branch
            // that reads `sweep-verdict=`, and its comment says every
            // sweep states its verdict that way — which was true of
            // the username sweeps and never true here. Without the
            // field the classifier fell through to reading the prose,
            // and the prose begins "Platform sweep — 'u'", so a sweep
            // that found nothing anywhere was filed as a finding and
            // could then vouch for other tools as a member of the
            // username-sweep independence family.
            val verdict = when {
                answered.isEmpty() -> UsernameChecker.VERDICT_UNREACHABLE
                hits.isNotEmpty() -> UsernameChecker.VERDICT_HIT
                // Some of the list answered and said no, and the rest
                // was never asked: that is neither an absence nor a
                // failure, and the field has a word for it.
                unreachable.isEmpty() -> UsernameChecker.VERDICT_ABSENT
                else -> UsernameChecker.VERDICT_PARTIAL
            }
            append(UsernameChecker.VERDICT_FIELD).append(verdict).append('\n')
            if (answered.isEmpty()) {
                append("Platform sweep could not be reached for '$u'")
                append(" — no platform answered, so this says nothing either way.\n")
                append("Not asked: ${unreachable.joinToString(", ")}")
                return@buildString
            }
            append("Platform sweep — '$u'\n")
            append("${hits.size} of ${answered.size} platforms that answered ")
            append("have an account under this name.\n")
            hits.forEach { (name, detail) ->
                append("\n▸ $name\n")
                append(
                    detail.getOrNull().orEmpty().lines()
                        .joinToString("\n") { if (it.startsWith("  ")) it else "  $it" }
                )
                append("\n")
            }
            if (misses.isNotEmpty()) append("\nNo account found on: ${misses.joinToString(", ")}")
            if (unreachable.isNotEmpty()) {
                append("\nCould not be reached, so nothing is claimed about these: ")
                append(unreachable.joinToString(", "))
            }
            append("\n\nChecked without a key or an account.")
        }
    }

    /** OpenStreetMap edit history — where someone maps is often where they
     *  live. Each changeset carries a bounding box. */
    fun osmUser(username: String): Result<String> = runCatching {
        val u = username.trim()
        val e = URLEncoder.encode(u, "UTF-8")
        val sets = json.parseToJsonElement(
            get("https://api.openstreetmap.org/api/0.6/changesets.json?display_name=$e")
        ).jsonObject["changesets"] as? JsonArray
        if (sets.isNullOrEmpty()) return@runCatching "OpenStreetMap: no edits by '$u'."
        val sb = StringBuilder("OpenStreetMap — $u\n${sets.size} recent changesets\n")
        val centres = ArrayList<Pair<Double, Double>>()
        sets.take(25).forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val tags = o["tags"] as? JsonObject
            // A lifetime changeset count was read from here and
            // printed as "Lifetime changesets: N". changesets_count is
            // a field of the OSM user object; a changeset's tags are
            // free text a mapper writes, so this read nothing almost
            // always and, on the day somebody used that word as a tag,
            // would have printed their sentence as this account's
            // lifetime total.
            val minLat = o.dbl("min_lat"); val maxLat = o.dbl("max_lat")
            val minLon = o.dbl("min_lon"); val maxLon = o.dbl("max_lon")
            sb.append("\n• ${o.str("created_at")?.substringBefore('T')}")
            tags.str("comment")?.let { sb.append(" — ${it.take(80)}") }
            if (minLat != null && maxLat != null && minLon != null && maxLon != null) {
                val cLat = (minLat + maxLat) / 2; val cLon = (minLon + maxLon) / 2
                centres.add(cLat to cLon)
                sb.append("\n  Near %.4f, %.4f".format(Locale.US, cLat, cLon))
            }
            tags.str("created_by")?.let { sb.append("\n  Editor: $it") }
        }

        if (centres.isNotEmpty()) {
            // Averaged on the sphere, and only reported if it means
            // anything.
            //
            // Two things were wrong with the arithmetic mean. Edits
            // at +179 and -179 average to zero — the Gulf of Guinea
            // instead of Fiji — because longitude wraps and nothing
            // here knew that. And the mean of a set with two
            // clusters is a place in between that the person has
            // never been: somebody who maps in Berlin and in Tokyo
            // got a centre in Siberia, printed under the sentence
            // "people overwhelmingly map where they live", which
            // turns a meaningless number into a claim about
            // somebody's home.
            //
            // So: a proper spherical mean, and a spread test before
            // it is called a centre at all.
            var x = 0.0; var y = 0.0; var z = 0.0
            centres.forEach { (la, lo) ->
                val rLat = Math.toRadians(la)
                val rLon = Math.toRadians(lo)
                x += cos(rLat) * cos(rLon)
                y += cos(rLat) * sin(rLon)
                z += sin(rLat)
            }
            val n = centres.size
            x /= n; y /= n; z /= n
            val hyp = sqrt(x * x + y * y)
            if (hyp > 1e-9 || kotlin.math.abs(z) > 1e-9) {
                val aLat = Math.toDegrees(atan2(z, hyp))
                val aLon = Math.toDegrees(atan2(y, x))
                val spread = centres.maxOf {
                    com.osint.unified.ui.godeye.AreaReport.distanceKm(aLat, aLon, it.first, it.second)
                }
                if (spread <= 300) {
                    sb.append("\nCentre of recent activity: %.4f, %.4f".format(Locale.US, aLat, aLon))
                    sb.append("\nEvery recent edit is within ${spread.toInt()} km of it.")
                    sb.append("\n(People overwhelmingly map where they live and travel.)")
                } else {
                    sb.append("\nRecent edits are spread over ${spread.toInt()} km, so ")
                    sb.append("there is no single centre to give — an average of ")
                    sb.append("scattered points is a place nobody has been. The ")
                    sb.append("per-changeset positions above are the usable ones.")
                }
            }
        }
        sb.append("\nhttps://www.openstreetmap.org/user/${seg(u)}")
        sb.toString()
    }

    // ------------------------------------------------------------------
    // Email
    // ------------------------------------------------------------------

    /** XposedOrNot: which public breaches an address appears in. Keyless. */
    fun breaches(email: String): Result<String> = runCatching {
        val e = URLEncoder.encode(email.trim(), "UTF-8")
        val body = get("https://api.xposedornot.com/v1/check-email/$e")
        val arr = json.parseToJsonElement(body).jsonObject["breaches"] as? JsonArray
        val names = (arr?.firstOrNull() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        if (names.isEmpty()) return@runCatching "Breach check: $email not found in any indexed public breach."
        val sb = StringBuilder("Breach exposure — $email\nFound in ${names.size} public breaches:\n")
        names.sorted().forEach { sb.append("• $it\n") }
        // The analytics endpoint adds what kind of data leaked and when.
        runCatching {
            val a = json.parseToJsonElement(get("https://api.xposedornot.com/v1/breach-analytics?email=$e")).jsonObject
            val risk = ((a["BreachMetrics"] as? JsonObject)?.get("risk") as? JsonArray)
                ?.firstOrNull()?.jsonObject
            risk?.str("risk_label")?.let { sb.append("\nRisk label: $it (score ${risk.int("risk_score") ?: 0})\n") }
            val xposed = ((a["ExposedBreaches"] as? JsonObject)?.get("breaches_details") as? JsonArray)
            xposed?.take(12)?.forEach { d ->
                val o = d as? JsonObject ?: return@forEach
                sb.append("\n• ${o.str("breach")} (${o.str("xposed_date") ?: "?"})")
                o.str("xposed_data")?.let { sb.append("\n  Leaked: ${it.replace(";", ", ")}") }
                o.str("industry")?.let { sb.append("\n  Industry: $it") }
            }
        }
        sb.append("\n\nSource: xposedornot.com (free, keyless)")
        sb.toString()
    }

    /** Maps an email to a GitHub account by searching the commit index. */
    fun emailToGithub(email: String): Result<String> = runCatching {
        val e = URLEncoder.encode(email.trim(), "UTF-8")
        val items = json.parseToJsonElement(get("https://api.github.com/search/users?q=$e+in:email"))
            .jsonObject["items"] as? JsonArray
        if (items.isNullOrEmpty()) return@runCatching "GitHub: no account publicly lists $email."
        val sb = StringBuilder("GitHub accounts using $email:\n")
        items.take(5).forEach {
            val o = it as? JsonObject ?: return@forEach
            sb.append("• ${o.str("login")} — ${o.str("html_url")}\n")
        }
        sb.toString()
    }

    // ------------------------------------------------------------------
    // Domain / infrastructure
    // ------------------------------------------------------------------

    /** Subdomain sweep across three independent keyless sources, merged. */
    fun subdomainSweep(domain: String): Result<String> = runCatching {
        val asked = Reached()
        val d = domain.trim().lowercase().removePrefix("www.")
        val found = LinkedHashMap<String, MutableSet<String>>()
        fun record(host: String, src: String) {
            val h = host.trim().lowercase().removePrefix("*.").removeSuffix(".")
            // The domain itself, or something genuinely under it.
            //
            // A bare endsWith made "notexample.com" a subdomain of
            // "ample.com" — and the wildcard queries these sources are
            // given do return neighbours, so this was not theoretical.
            // A host attributed to the wrong owner then goes into the
            // dossier, and into the corroboration pass as a fact about
            // the target.
            val belongs = h == d || h.endsWith(".$d")
            if (belongs && h.isNotEmpty()) found.getOrPut(h) { LinkedHashSet() }.add(src)
        }
        asked.answering {
            // Same quota notice as the host-intel path. Here it
            // would be filed as a subdomain — except that the
            // belongs test above throws it out, which is luck rather
            // than design. And a spent quota used to count as a
            // source that had looked, which is what turned
            // "hackertarget refused" into "Subdomain sweep: nothing
            // found for example.com".
            val body = get("https://api.hackertarget.com/hostsearch/?q=$d")
            if (!hackertargetAnswered(body)) return@answering false
            body.lineSequence().forEach {
                val host = it.substringBefore(",").trim()
                if (host.isNotEmpty()) record(host, "hackertarget")
            }
            true
        }
        asked.of {
            // RapidDNS publishes an HTML table; each row's first cell is a host.
            val html = get("https://rapiddns.io/subdomain/$d?full=1", browserUa)
            Regex("""<td[^>]*>([A-Za-z0-9_.*-]+\.${Regex.escape(d)})</td>""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { record(it.groupValues[1], "rapiddns") }
        }
        asked.of {
            // Cert Spotter indexes certificate transparency independently of crt.sh.
            val arr = json.parseToJsonElement(
                get("https://api.certspotter.com/v1/issuances?domain=$d&include_subdomains=true&expand=dns_names")
            ) as? JsonArray
            arr?.forEach { el ->
                ((el as? JsonObject)?.get("dns_names") as? JsonArray)?.forEach { n ->
                    (n as? JsonPrimitive)?.contentOrNull?.let { record(it, "certspotter") }
                }
            }
        }
        asked.of {
            // Wayback's CDX index knows hosts that no longer resolve.
            get("https://web.archive.org/cdx/search/cdx?url=*.$d&output=text&fl=original&collapse=urlkey&limit=4000")
                .lineSequence().forEach { line ->
                    val host = line.substringAfter("://", "").substringBefore("/").substringBefore(":")
                    if (host.isNotEmpty()) record(host, "wayback")
                }
        }
        if (found.isEmpty()) {
            // Four sources, each in its own runCatching, and an empty
            // result said the domain has no subdomains rather than
            // that nobody answered. `asked` counts the ones that did.
            return@runCatching if (asked.any) {
                "Subdomain sweep: nothing found for $d."
            } else {
                "No subdomain source could be reached for $d, so nothing " +
                    "is claimed about its subdomains."
            }
        }
        // Named from what actually answered. The header used to list
        // three fixed sources whatever had run — certspotter was
        // queried too and never credited, and a host found only by one
        // of them was attributed to all three.
        val answered = found.values.flatten().distinct().sorted()
        val sb = StringBuilder(
            "Subdomain sweep — $d\n${found.size} unique hosts from " +
                "${answered.joinToString(" + ")}:\n\n"
        )
        found.keys.sorted().forEach { h -> sb.append("$h  [${found[h]?.joinToString("/")}]\n") }
        sb.toString()
    }

    /**
     * Whether hackertarget actually answered the question asked.
     *
     * Its free tier replies HTTP 200 with a sentence when the daily
     * quota is spent — "API count exceeded - Increase Quota with
     * Membership" — and the guard only rejected bodies starting with
     * "error". So the quota notice was printed as the one other site
     * on the target's IP, with a bullet in front of it.
     */
    private fun hackertargetAnswered(body: String): Boolean {
        if (body.isBlank()) return false
        val head = body.lineSequence().firstOrNull()?.lowercase().orEmpty()
        if (head.startsWith("error")) return false
        return NOT_AN_ANSWER.none { head.contains(it) }
    }

    private val NOT_AN_ANSWER = listOf(
        "api count exceeded", "increase quota", "api limit",
        "no records found", "invalid", "rate limit"
    )

    /** hackertarget's keyless toolbox: reverse IP, AS lookup, page links. */
    fun hostIntel(input: String): Result<String> = runCatching {
        val t = input.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val sb = StringBuilder("Host intel — $t\n")
        // Measured, not guessed.
        //
        // The test for "found nothing" was a magic number against the
        // whole buffer — thirty here, forty and forty-five elsewhere —
        // and the header is "Host intel — " plus the target. So for
        // any target longer than about sixteen characters the header
        // alone cleared the threshold and a lookup that found nothing
        // returned the header, which the classifier then read as a
        // finding. The verdict depended on the length of the domain
        // name. Comparing against the header's own length is what
        // those numbers were trying to approximate.
        val headerOnly = sb.length
        val q = java.net.URLEncoder.encode(t, "UTF-8")
        val reached = Reached()
        reached.answering {
            val r = get("https://api.hackertarget.com/reverseiplookup/?q=$q").trim()
            if (!hackertargetAnswered(r)) return@answering false
            val hosts = r.lines().filter { it.isNotBlank() }
            sb.append("\nOther sites on this IP (${hosts.size}):\n")
            hosts.take(60).forEach { sb.append("• $it\n") }
            sb.andMore(hosts.size, 60)
            true
        }
        reached.answering {
            val a = get("https://api.hackertarget.com/aslookup/?q=$q").trim()
            if (!hackertargetAnswered(a)) return@answering false
            sb.append("\nAS: $a\n")
            true
        }
        reached.answering {
            val l = get("https://api.hackertarget.com/pagelinks/?q=http%3A%2F%2F$q").trim()
            if (!hackertargetAnswered(l)) return@answering false
            val links = l.lines().filter { it.isNotBlank() }
            // The host, not a substring of the whole link.
            //
            // `contains` calls a link to notexample.com internal
            // when the target is ample.com, and calls
            // evil.com/?ref=ample.com internal too — so outbound
            // links to other people's sites were dropped from
            // the count, which is the one number this section
            // exists to give.
            val external = links.filterNot { sameSite(it, t) }.distinct()
            sb.append("\nOutbound links (${external.size} external):\n")
            external.take(40).forEach { sb.append("• $it\n") }
            sb.andMore(external.size, 40)
            true
        }
        if (sb.length > headerOnly) sb.toString()
        else if (reached.any) "Host intel: no data returned for $t."
        else "Could not reach hackertarget for $t, so nothing is claimed either way."
    }

    /** A marker, because this one is not a prefix. */
    private val MASTODON = "\u0000mastodon"

    /** Whether a link points at this host or under it. */
    private fun sameSite(link: String, host: String): Boolean {
        val h = link.trim()
            .substringAfter("://", link.trim())
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
            .removePrefix("www.")
        val target = host.lowercase().removePrefix("www.")
        return h == target || h.endsWith(".$target")
    }

    /**
     * IP reputation across open DNS blocklists, queried over DNS-over-HTTPS.
     *
     * Several lists (Spamhaus, SpamCop) refuse queries that arrive via a
     * public resolver and answer with a 127.255.255.x code instead of a real
     * listing. That is reported as "refused", never as a hit — a false
     * positive here would be worse than no answer.
     */
    fun ipReputation(input: String): Result<String> = runCatching {
        val ip = input.trim()
        if (!Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(ip)) {
            return@runCatching "IP reputation needs an IPv4 address."
        }
        val reversed = ip.split(".").reversed().joinToString(".")
        val lists = listOf(
            "dnsbl.dronebl.org" to "DroneBL (bots, proxies, shells)",
            "all.s5h.net" to "s5h (spam sources)",
            "b.barracudacentral.org" to "Barracuda",
            "zen.spamhaus.org" to "Spamhaus ZEN",
            "dnsbl-1.uceprotect.net" to "UCEPROTECT level 1",
            "bl.blocklist.de" to "blocklist.de (attack reports)"
        )
        val listed = ArrayList<String>()
        val clean = ArrayList<String>()
        val refused = ArrayList<String>()
        val unreachable = ArrayList<String>()
        lists.forEach { (zone, label) ->
            // Null when the resolver did not answer, which is not the
            // same as a blocklist saying no. This tool's own
            // description promises it "reports refusals honestly
            // rather than as hits", and the one case it did not
            // distinguish was the one where nothing answered at all —
            // six failed lookups read as six clean sheets.
            val answers = runCatching {
                val o = json.parseToJsonElement(
                    get("https://dns.google/resolve?type=A&name=$reversed.$zone")
                ).jsonObject
                // The resolver's own status, which was not being read.
                //
                // dns.google answers HTTP 200 for SERVFAIL (2) and
                // REFUSED (5) with no Answer array — which is exactly
                // what a blocklist returns to a public resolver it is
                // rate limiting, and the doc comment above promises
                // that is reported as refused and never as a hit. An
                // absent Answer was being read as "not listed". 0 is
                // NOERROR and 3 is NXDOMAIN, and for a blocklist
                // NXDOMAIN is the answer "not on this list".
                val status = (o["Status"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (status != null && status != 0 && status != 3) {
                    throw IOException("resolver status $status")
                }
                (o["Answer"] as? JsonArray)?.mapNotNull { (it as? JsonObject).str("data") } ?: emptyList()
            }.getOrNull()
            when {
                answers == null -> unreachable.add(label)
                answers.isEmpty() -> clean.add(label)
                answers.any { it.startsWith("127.255.255.") } -> refused.add(label)
                else -> listed.add("$label → ${answers.joinToString(", ")}")
            }
        }
        // Refused is not answered.
        //
        // The guard needed all three to be empty, so six lists all
        // refusing a public resolver passed it — and the line below
        // then printed "Not listed on any blocklist that answered",
        // a clean verdict out of zero answers, with the qualifying
        // note twelve lines further down where the classifier never
        // looks.
        if (clean.isEmpty() && listed.isEmpty()) {
            return@runCatching "IP reputation could not be reached for $ip: " +
                "no blocklist gave a usable answer" +
                (if (refused.isNotEmpty()) {
                    " (${refused.size} refused the public resolver)"
                } else {
                    ""
                }) +
                ", so this says nothing either way."
        }
        buildString {
            append("IP reputation — $ip\n")
            if (listed.isEmpty()) {
                append("\nNot listed on any of the ${clean.size} blocklists that answered.\n")
            } else {
                append("\nLISTED on ${listed.size} blocklist(s):\n")
                listed.forEach { append("• $it\n") }
            }
            if (clean.isNotEmpty()) append("\nClean on: ${clean.joinToString(", ")}\n")
            if (unreachable.isNotEmpty()) {
                append("\nDid not answer, so nothing is claimed about these: ")
                append(unreachable.joinToString(", "))
                append("\n")
            }
            if (refused.isNotEmpty()) {
                append("\nNo answer (these lists refuse public-resolver queries, ")
                append("so this is not a clean result): ${refused.joinToString(", ")}\n")
            }
            append("\nChecked over DNS-over-HTTPS. 127.0.0.x codes mean a listing; ")
            append("each list documents what its codes mean.")
        }
    }

    /**
     * Who actually runs the network an address sits on: the RIR abuse
     * contact, how much space the AS announces, and its PeeringDB record
     * (org, traffic profile, exchanges, facilities).
     */
    fun asnIntel(input: String): Result<String> = runCatching {
        val t = input.trim().uppercase().removePrefix("AS").let { if (it.all(Char::isDigit)) "AS$it" else input.trim() }
        val sb = StringBuilder("Network intel — $t\n")
        val headerOnly = sb.length
        val reached = Reached()

        reached.of {
            val d = json.parseToJsonElement(
                get("https://stat.ripe.net/data/abuse-contact-finder/data.json?resource=${URLEncoder.encode(t, "UTF-8")}")
            ).jsonObject["data"]?.jsonObject
            (d?.get("abuse_contacts") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?.let { sb.append("Abuse contact: ${it.joinToString(", ")}\n") }
            d.str("authoritative_rir")?.let { sb.append("RIR: ${it.uppercase()}\n") }
        }

        // Resolve an IP to its AS so the PeeringDB lookup has something to use.
        var asn = Regex("""^AS(\d+)$""").find(t)?.groupValues?.get(1)
        if (asn == null) {
            reached.of {
                val d = json.parseToJsonElement(
                    get("https://stat.ripe.net/data/network-info/data.json?resource=${URLEncoder.encode(t, "UTF-8")}")
                ).jsonObject["data"]?.jsonObject
                d.str("prefix")?.let { sb.append("Prefix: $it\n") }
                asn = (d?.get("asns") as? JsonArray)?.firstOrNull()
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
        }

        asn?.let { a ->
            sb.append("ASN: AS$a\n")
            reached.of {
                val prefixes = (json.parseToJsonElement(
                    get("https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS$a")
                ).jsonObject["data"]?.jsonObject?.get("prefixes") as? JsonArray)
                prefixes?.let { sb.append("Announced prefixes: ${it.size}\n") }
            }
            reached.of {
                val net = (json.parseToJsonElement(get("https://www.peeringdb.com/api/net?asn=$a"))
                    .jsonObject["data"] as? JsonArray)?.firstOrNull()?.jsonObject
                if (net != null) {
                    sb.append("\nPeeringDB:\n")
                    net.str("name")?.let { sb.append("• Operator: $it\n") }
                    net.str("aka")?.takeIf { it.isNotBlank() }?.let { sb.append("• Also known as: $it\n") }
                    net.str("website")?.takeIf { it.isNotBlank() }?.let { sb.append("• Website: $it\n") }
                    net.str("info_type")?.let { sb.append("• Network type: $it\n") }
                    net.str("info_scope")?.let { sb.append("• Scope: $it\n") }
                    net.str("info_traffic")?.takeIf { it.isNotBlank() }?.let { sb.append("• Traffic: $it\n") }
                    net.int("ix_count")?.let { sb.append("• Internet exchanges: $it\n") }
                    net.int("fac_count")?.let { sb.append("• Facilities: $it\n") }
                    net.str("policy_general")?.let { sb.append("• Peering policy: $it\n") }
                }
            }
        }
        if (sb.length > headerOnly) sb.toString()
        else if (reached.any) "No network data for $t."
        else "Could not reach RIPEstat or PeeringDB for $t, so nothing is claimed either way."
    }

    /** AlienVault OTX, keyless: passive DNS, hosted URLs and malware seen on
     *  a domain or IP. */
    fun threatExchange(input: String): Result<String> = runCatching {
        val t = input.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val kind = if (Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(t)) "IPv4" else "domain"
        val base = "https://otx.alienvault.com/api/v1/indicators/$kind/$t"
        val sb = StringBuilder("Threat exchange (OTX) — $t\n")
        val headerOnly = sb.length
        val reached = Reached()

        reached.of {
            val o = json.parseToJsonElement(get("$base/general")).jsonObject
            o.int("pulse_count")?.let { sb.append("Threat reports mentioning it: $it\n") }
            val pulses = ((o["pulse_info"] as? JsonObject)?.get("pulses") as? JsonArray)
            pulses?.take(8)?.forEach { p ->
                val po = p as? JsonObject ?: return@forEach
                sb.append("• ${po.str("name")?.take(90)}")
                po.str("created")?.let { sb.append(" (${it.substringBefore('T')})") }
                sb.append("\n")
            }
            (o["validation"] as? JsonArray)?.mapNotNull { (it as? JsonObject).str("message") }
                ?.takeIf { it.isNotEmpty() }?.let { sb.append("Reputation notes: ${it.joinToString("; ")}\n") }
        }

        reached.of {
            val rows = json.parseToJsonElement(get("$base/passive_dns"))
                .jsonObject["passive_dns"] as? JsonArray
            rows?.takeIf { it.isNotEmpty() }?.let { arr ->
                sb.append("\nPassive DNS — ${arr.size} historical resolutions:\n")
                arr.take(30).forEach { r ->
                    val o = r as? JsonObject ?: return@forEach
                    sb.append("• ${o.str("hostname") ?: o.str("address")}")
                    o.str("address")?.takeIf { it != o.str("hostname") }?.let { sb.append(" → $it") }
                    o.str("first")?.let { sb.append("  first ${it.substringBefore('T')}") }
                    o.str("last")?.let { sb.append("  last ${it.substringBefore('T')}") }
                    sb.append("\n")
                }
                sb.andMore(arr.size, 30)
            }
        }

        reached.of {
            val rows = json.parseToJsonElement(get("$base/url_list?limit=20"))
                .jsonObject["url_list"] as? JsonArray
            rows?.takeIf { it.isNotEmpty() }?.let { arr ->
                sb.append("\nURLs seen (${arr.size}):\n")
                arr.take(20).forEach { r ->
                    sb.append("• ${(r as? JsonObject).str("url")?.take(140)}\n")
                }
                sb.andMore(arr.size, 20)
            }
        }
        if (sb.length > headerOnly) sb.toString()
        else if (reached.any) "OTX has nothing on $t."
        else "Could not reach AlienVault OTX for $t, so nothing is claimed either way."
    }

    /** Internet outages happening right now, worldwide (Georgia Tech IODA). */
    fun outages(input: String): Result<String> = runCatching {
        val now = System.currentTimeMillis() / 1000
        val from = now - 24 * 3600
        val rows = json.parseToJsonElement(
            get("https://api.ioda.inetintel.cc.gatech.edu/v2/outages/alerts?from=$from&until=$now&limit=120")
        ).jsonObject["data"] as? JsonArray
        if (rows.isNullOrEmpty()) return@runCatching "IODA reports no internet outage alerts in the last 24 hours."
        val filter = input.trim().lowercase().takeIf { it.isNotBlank() && it != "osint" }
        val sb = StringBuilder("Internet outages — last 24 hours\n")
        val seen = LinkedHashMap<String, String>()
        rows.forEach { r ->
            val o = r as? JsonObject ?: return@forEach
            val ent = o["entity"] as? JsonObject ?: return@forEach
            val name = ent.str("name") ?: return@forEach
            val org = ((ent["attrs"] as? JsonObject)).str("org")
            val level = o.str("level") ?: ""
            if (filter != null && !name.lowercase().contains(filter) &&
                org?.lowercase()?.contains(filter) != true
            ) return@forEach
            val key = "$name|$org"
            if (seen.containsKey(key)) return@forEach
            seen[key] = buildString {
                append("• $name")
                if (!org.isNullOrBlank()) append(" ($org)")
                append(" — $level")
                o.str("datasource")?.let { append(", via $it") }
            }
        }
        if (seen.isEmpty()) {
            return@runCatching "No outage alerts matching '${filter ?: ""}' in the last 24 hours."
        }
        sb.append("${seen.size} affected networks/regions:\n\n")
        seen.values.take(60).forEach { sb.append(it).append("\n") }
        sb.andMore(seen.size, 60)
        sb.append("\nSource: IODA (ioda.inetintel.cc.gatech.edu), keyless.")
        sb.toString()
    }

    /** Full Tor relay record for an IP or nickname, from the Tor Project's
     *  own Onionoo service. */
    fun torRelay(input: String): Result<String> = runCatching {
        val q = URLEncoder.encode(input.trim(), "UTF-8")
        val o = json.parseToJsonElement(get("https://onionoo.torproject.org/details?search=$q&limit=5")).jsonObject
        val relays = o["relays"] as? JsonArray
        if (relays.isNullOrEmpty()) {
            return@runCatching "Tor: '${input.trim()}' is not a known relay or bridge."
        }
        val sb = StringBuilder("Tor network — ${relays.size} matching relay(s)\n")
        relays.forEach { r ->
            val n = r as? JsonObject ?: return@forEach
            sb.append("\n• ${n.str("nickname") ?: "(unnamed)"}")
            (n["flags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }?.let { sb.append("  [${it.joinToString(", ")}]") }
            (n["or_addresses"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.let { sb.append("\n  Addresses: ${it.joinToString(", ")}") }
            n.str("country_name")?.let { sb.append("\n  Country: $it") }
            n.str("as_name")?.let { sb.append("\n  Network: $it (${n.str("as") ?: "?"})") }
            n.str("first_seen")?.let { sb.append("\n  First seen: ${it.substringBefore(' ')}") }
            n.str("last_seen")?.let { sb.append("\n  Last seen: ${it.substringBefore(' ')}") }
            n.int("observed_bandwidth")?.let { sb.append("\n  Bandwidth: ${it / 1024} KB/s") }
            n.str("contact")?.takeIf { it.isNotBlank() }?.let { sb.append("\n  Operator contact: $it") }
            n.str("platform")?.let { sb.append("\n  Platform: $it") }
            val exit = (n["flags"] as? JsonArray)?.any {
                (it as? JsonPrimitive)?.contentOrNull == "Exit"
            } == true
            if (exit) sb.append("\n  This is an EXIT relay — traffic leaves the Tor network here.")
        }
        sb.append("\n\nSource: Onionoo, the Tor Project's own directory service.")
        sb.toString()
    }

    // ------------------------------------------------------------------
    // Corporate, sanctions, wanted
    // ------------------------------------------------------------------

    /** GLEIF: the global register of legal entities. Address, status, parents. */
    fun legalEntity(name: String): Result<String> = runCatching {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val recs = json.parseToJsonElement(
            get("https://api.gleif.org/api/v1/lei-records?filter%5Bentity.legalName%5D=$q&page%5Bsize%5D=8")
        ).jsonObject["data"] as? JsonArray
        if (recs.isNullOrEmpty()) return@runCatching "GLEIF: no registered legal entity named '$name'."
        val sb = StringBuilder("Legal entities named '$name' (GLEIF, ${recs.size} shown):\n")
        recs.forEach { r ->
            val o = r as? JsonObject ?: return@forEach
            val attr = o["attributes"]?.jsonObject
            val ent = attr?.get("entity")?.jsonObject
            sb.append("\n• ${(ent?.get("legalName") as? JsonObject)?.str("name") ?: "?"}")
            sb.append("\n  LEI: ${attr?.str("lei")}")
            val addr = ent?.get("legalAddress")?.jsonObject
            val street = (addr?.get("addressLines") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(", ")
            sb.append("\n  Address: ${listOfNotNull(street, addr?.str("city"), addr?.str("region"), addr?.str("country"), addr?.str("postalCode")).joinToString(", ")}")
            ent?.str("status")?.let { sb.append("\n  Status: $it") }
            (ent?.get("legalForm") as? JsonObject)?.str("id")?.let { sb.append("\n  Legal form: $it") }
            (attr?.get("registration") as? JsonObject)?.str("status")?.let { sb.append("\n  Registration: $it") }
            (ent?.get("otherNames") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }
                ?.takeIf { it.isNotEmpty() }?.let { sb.append("\n  Other names: ${it.joinToString(", ")}") }
        }
        sb.toString()
    }

    /** SEC EDGAR company search — filings, CIK, business address, officers. */
    fun secEdgar(name: String): Result<String> = runCatching {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        // SEC requires a descriptive UA carrying a contact address, and it
        // must be the person's own rather than one baked into the build.
        val agent = SecAgent.userAgent(secContactEmail())
            ?: return@runCatching SecAgent.NOT_SET
        val xml = get(
            "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&company=$q&type=&dateb=&owner=include&count=20&output=atom",
            agent
        )
        val sb = StringBuilder("SEC EDGAR — '$name'\n")
        // Each registrant read out of its own entry.
        //
        // The two patterns used to be run independently over the whole
        // document and then zipped by index — with .distinct() applied
        // to one of them and not the other. Any feed where the two
        // sequences did not line up one-for-one printed a company
        // under another company's CIK and linked to the wrong
        // registrant's filings, silently and confidently.
        val entries = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()
        val registrants = entries.mapNotNull { entry ->
            val n = Regex("""<conformed-name>([^<]+)</conformed-name>""")
                .find(entry)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            n to Regex("""CIK=(\d{10})""").find(entry)?.groupValues?.get(1)
        }
        // A single match is one company's record, so its address is
        // that company's. A list of twenty is not, and labelling the
        // first entry's city as "the" business address was attaching
        // one registrant's details to all of them.
        if (entries.size <= 1) {
            Regex("""<city>([^<]+)</city>.*?<state>([^<]+)</state>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.let { sb.append("Business address: ${it.groupValues[1]}, ${it.groupValues[2]}\n") }
            Regex("""<phone>([^<]+)</phone>""").find(xml)?.let { sb.append("Phone: ${it.groupValues[1]}\n") }
            Regex("""<state-of-incorporation>([^<]+)<""").find(xml)?.let { sb.append("Incorporated in: ${it.groupValues[1]}\n") }
            Regex("""<assigned-sic-desc>([^<]+)<""").find(xml)?.let { sb.append("Industry: ${it.groupValues[1]}\n") }
        }
        val ciks = registrants.mapNotNull { it.second }
        val names = registrants.map { it.first }
        if (registrants.isNotEmpty()) {
            sb.append("\nCompanies")
            if (registrants.size >= 20) sb.append(" (the search returns at most 20)")
            sb.append(":\n")
            registrants.take(20).forEach { (n, cik) ->
                sb.append("• $n")
                if (cik != null) {
                    sb.append(" (CIK $cik)\n  https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$cik&type=&dateb=&owner=include&count=40\n")
                } else {
                    sb.append("\n")
                }
            }
        }
        val filings = Regex("""<filing-type>([^<]+)</filing-type>.*?<filing-date>([^<]+)</filing-date>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).take(15).toList()
        if (filings.isNotEmpty()) {
            sb.append("\nRecent filings:\n")
            filings.forEach { sb.append("• ${it.groupValues[1]} — ${it.groupValues[2]}\n") }
        }
        // efts.sec.gov is the JSON backend and refuses a browser; the page
        // a person can actually open is EDGAR's own full-text search.
        if (ciks.isEmpty() && names.isEmpty()) {
            // In front of the header: Findings.NOTHING has held
            // "no registrant matched" all along and it was dead
            // config, because only the first non-blank line is read
            // and the first line was "SEC EDGAR — 'Jane Doe'".
            return@runCatching buildString {
                append("No registrant matched '$name' in SEC EDGAR.\n")
                append("\nFull-text search: https://www.sec.gov/edgar/search/#/q=$q")
            }
        }
        sb.append("\nFull-text search: https://www.sec.gov/edgar/search/#/q=$q")
        sb.toString()
    }

    /** FBI wanted / seeking-information list, searched by name. */
    fun fbiWanted(name: String): Result<String> = runCatching {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val items = json.parseToJsonElement(get("https://api.fbi.gov/wanted/v1/list?title=$q&pageSize=10"))
            .jsonObject["items"] as? JsonArray
        if (items.isNullOrEmpty()) return@runCatching "FBI wanted list: no entry matching '$name'."
        val sb = StringBuilder("FBI wanted / seeking information — '$name' (${items.size} matches):\n")
        items.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            sb.append("\n• ${o.str("title")}")
            o.str("description")?.takeIf { it.isNotBlank() }?.let { sb.append("\n  $it") }
            (o["subjects"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }?.let { sb.append("\n  Category: ${it.joinToString(", ")}") }
            (o["field_offices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }?.let { sb.append("\n  Field office: ${it.joinToString(", ")}") }
            o.str("reward_text")?.let { sb.append("\n  Reward: ${it.take(140)}") }
            o.str("url")?.let { sb.append("\n  $it") }
        }
        sb.append("\n\nSource: FBI public API (api.fbi.gov)")
        sb.toString()
    }

    /** Public PGP key for an address — user IDs on a key often carry a real
     *  name and other addresses the person uses. */
    fun pgpKey(email: String): Result<String> = runCatching {
        val e = URLEncoder.encode(email.trim(), "UTF-8")
        // The keyserver's own 404 means no key; a DNS failure means
        // nothing at all. Both used to come out as a statement about
        // what keys.openpgp.org holds.
        val attempt = runCatching { get("https://keys.openpgp.org/vks/v1/by-email/$e") }
        val code = httpCode(attempt.exceptionOrNull() ?: IOException(""))
        val body = attempt.getOrNull()
        if (attempt.isFailure && code != 404) {
            return@runCatching "keys.openpgp.org could not be reached for " +
                "${email.trim()}, so nothing is claimed about a published key.\n" +
                "To try by hand: https://keyserver.ubuntu.com/pks/lookup?search=$e&op=index"
        }
        if (body.isNullOrBlank() || !body.contains("BEGIN PGP")) {
            return@runCatching "No PGP key published for ${email.trim()} on keys.openpgp.org.\n" +
                "Other keyservers to try by hand: https://keyserver.ubuntu.com/pks/lookup?search=$e&op=index"
        }
        buildString {
            append("PGP key found for ${email.trim()}\n")
            append("Published on keys.openpgp.org, which verifies the address before listing it, ")
            append("so this is reasonable evidence the address is real and controlled by its owner.\n\n")
            append("Key block (${body.length} bytes):\n")
            append(body.lineSequence().take(4).joinToString("\n"))
            append("\n…\n\nFull key: https://keys.openpgp.org/vks/v1/by-email/$e")
        }
    }

    /** Historic URLs for a domain from the Common Crawl index — pages that
     *  may no longer be linked anywhere. */
    fun commonCrawl(domain: String): Result<String> = runCatching {
        val d = domain.trim().lowercase().removePrefix("www.")
        // No guessed index name.
        //
        // The fallback was a hardcoded crawl id, which is correct
        // for about three months and then addresses an index that
        // does not exist — and the 404 from that is reported as the
        // source failing rather than as the app asking for the
        // wrong thing. If the index list cannot be read, that is
        // what gets said.
        val index = runCatching {
            (json.parseToJsonElement(get("https://index.commoncrawl.org/collinfo.json")) as? JsonArray)
                ?.firstOrNull()?.jsonObject?.str("id")
        }.getOrNull()
            ?: return@runCatching "Common Crawl's index list could not be read, " +
                "so there is no way to know which crawl to search. Nothing is " +
                "claimed about $d either way."
        val body = get(
            "https://index.commoncrawl.org/$index-index?url=" +
                java.net.URLEncoder.encode("*.$d", "UTF-8") +
                "&output=json&limit=300"
        )
        val urls = LinkedHashSet<String>()
        val statuses = LinkedHashMap<String, Int>()
        body.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            runCatching {
                val o = json.parseToJsonElement(line).jsonObject
                o.str("url")?.let { urls.add(it) }
                o.str("status")?.let { statuses[it] = (statuses[it] ?: 0) + 1 }
            }
        }
        if (urls.isEmpty()) return@runCatching "Common Crawl has no indexed pages for $d in $index."
        buildString {
            append("Common Crawl ($index) — $d\n${urls.size} indexed URLs\n")
            if (statuses.isNotEmpty()) {
                append("Status codes: ${statuses.entries.joinToString(", ") { "${it.key}×${it.value}" }}\n")
            }
            val hosts = urls.mapNotNull {
                runCatching { it.substringAfter("://").substringBefore("/") }.getOrNull()
            }.distinct()
            append("Hosts seen: ${hosts.size}\n")
            hosts.take(40).forEach { append("• $it\n") }
            andMore(hosts.size, 40)
            append("\nSample URLs:\n")
            urls.take(60).forEach { append("$it\n") }
            andMore(urls.size, 60)
        }
    }

    /** Photographs taken near a coordinate — Wikimedia Commons plus
     *  street-level imagery from KartaView and Panoramax. */
    fun photosNear(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        val sb = StringBuilder("Imagery near %.5f, %.5f\n".format(Locale.US, lat, lon))
        val headerOnly = sb.length
        // Which of the three were actually asked.
        //
        // All three were in bare runCatching blocks, so on a flaky
        // connection — or with all three hosts slow — the buffer stayed
        // empty and the far end of this function rewrote it to "No
        // geotagged imagery found near 51.50735, -0.12776" for a London
        // street corner with thousands of photographs of it. A stated
        // clean sheet produced entirely by the user's own network, and
        // counted as a source that answered.
        val reached = Reached()

        reached.of {
            // `query` is absent both when the search found nothing
            // and when Commons refused, and the second was printing
            // "No geotagged imagery found near …" — the exact
            // sentence the comment above says was the bug. MediaWiki
            // raises on the refusal; a genuine empty result returns a
            // body with `query` present or with neither, so the
            // no-results-block case is handled by `body` rather than
            // `query` here.
            val answer = MediaWiki.body(
                get(
                    "https://commons.wikimedia.org/w/api.php?action=query&generator=geosearch" +
                        "&ggscoord=$lat%7C$lon&ggsradius=3000&ggslimit=40&prop=imageinfo" +
                        "&iiprop=url%7Cextmetadata&format=json"
                ),
                "Commons geosearch"
            )
            val pages = (answer["query"] as? JsonObject)?.get("pages") as? JsonObject
            if (pages != null && pages.isNotEmpty()) {
                sb.append("\nWikimedia Commons — ${pages.size} geotagged items within 3 km:\n")
                pages.values.take(40).forEach { p ->
                    val o = p as? JsonObject ?: return@forEach
                    sb.append("• ${o.str("title")}")
                    val info = (o["imageinfo"] as? JsonArray)?.firstOrNull()?.jsonObject
                    info.str("url")?.let { sb.append("\n  $it") }
                    sb.append("\n")
                }
                sb.andMore(pages.size, 40)
            }
        }

        reached.of {
            val data = ((json.parseToJsonElement(
                // api.kartaview.org, not the openstreetcam.org alias it
                // used to be called. Both answer with the same bytes, but
                // the legacy name is served through a redirect that took
                // eleven and a half seconds to the current name's four
                // hundredths — street-level imagery looked broken on a
                // phone for no reason but the hostname.
                get("https://api.kartaview.org/2.0/photo/?lat=$lat&lng=$lon&radius=400")
            ).jsonObject["result"] as? JsonObject)?.get("data") as? JsonArray)
            if (!data.isNullOrEmpty()) {
                // KartaView returns its file URLs with a {{sizeprefix}}
                // placeholder where the size directory belongs; served
                // verbatim it is a dead link that answers BlobNotFound.
                // "lth" is the large thumbnail, which is what a person
                // following one of these actually wants to look at.
                fun photoUrl(raw: String?) = raw?.replace("{{sizeprefix}}", "lth")

                // Newest first, and the age is spelled out: street-level
                // imagery is frequently years old, and a six-year-old
                // photograph offered without its date is the dossier
                // implying it shows the place as it is now.
                val sorted = data.mapNotNull { it as? JsonObject }
                    .sortedByDescending { it.str("dateAdded").orEmpty() }
                sb.append("\nKartaView street-level — ${data.size} photos within 400 m")
                sb.append(", newest first:\n")
                sorted.take(25).forEach { o ->
                    val taken = o.str("dateAdded")?.substringBefore(' ')
                    sb.append("• ${taken ?: "date unknown"}")
                    Freshness.ago(Freshness.parse(taken))?.let { sb.append(" ($it)") }
                    o.str("distance")?.let { sb.append("  ${it}m away") }
                    photoUrl(o.str("fileurl"))?.let { sb.append("\n  $it") }
                    sb.append("\n")
                }
                sb.andMore(data.size, 25)
            }
        }

        // Panoramax: open street-level imagery, keyless, worldwide, and
        // it hands back the JPEG itself rather than a viewer. Added
        // because the only other street-level option this app could
        // name was a link to a WebGL application that needs a key to
        // query and most of a minute to paint on a phone — a source
        // you cannot read is not a source.
        reached.of {
            // A degree of latitude is 111 km everywhere; a degree of
            // longitude is that times the cosine of the latitude, so a
            // square box in metres is not a square box in degrees.
            val dLat = PANORAMAX_RADIUS_M / 111_320.0
            val dLon = dLat / kotlin.math.max(
                kotlin.math.cos(Math.toRadians(lat)), 0.02
            )
            val bbox = "%.6f,%.6f,%.6f,%.6f".format(
                Locale.US, lon - dLon, lat - dLat, lon + dLon, lat + dLat
            )
            val feats = json.parseToJsonElement(
                get("https://api.panoramax.xyz/api/search?bbox=$bbox&limit=$PANORAMAX_LIMIT")
            ).jsonObject["features"] as? JsonArray
            // Filtered to a circle, because the query is a box.
            //
            // A box of 400 m on each axis has corners 566 m from the
            // point, and the line below called every photo in it
            // "within 400 m" — a photograph of the next street but one,
            // described to the reader as being at this corner. There is
            // no radius parameter, so the box is asked for and the
            // distance checked here.
            val rows = feats?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .filter { f ->
                    val c = (f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray
                    val plon = (c?.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    val plat = (c?.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    plat == null || plon == null ||
                        metresBetween(lat, lon, plat, plon) <= PANORAMAX_RADIUS_M
                }
            if (rows.isNotEmpty()) {
                // "At least", because limit=30 is this app's page size
                // and not a count of what is there. It was printed as a
                // total.
                val capped = rows.size >= PANORAMAX_LIMIT
                sb.append("\nPanoramax street-level — ")
                sb.append(if (capped) "at least " else "")
                sb.append("${rows.size} photos within ")
                sb.append("${PANORAMAX_RADIUS_M.toInt()} m, newest first:\n")
                rows.sortedByDescending {
                    (it["properties"] as? JsonObject).str("datetime").orEmpty()
                }.take(20).forEach { f ->
                    val props = f["properties"] as? JsonObject
                    val taken = props.str("datetime")?.substringBefore('T')
                    sb.append("• ${taken ?: "date unknown"}")
                    Freshness.ago(Freshness.parse(taken))?.let { sb.append(" ($it)") }
                    // The direction the camera was pointing, which is the
                    // difference between "there is a photo of this corner"
                    // and "there is a photo looking at this building".
                    props.dbl("view:azimuth")
                        ?.let { sb.append("  facing ${it.toInt()}°") }
                    val assets = f["assets"] as? JsonObject
                    val href = (assets?.get("sd") as? JsonObject).str("href")
                        ?: (assets?.get("hd") as? JsonObject).str("href")
                    href?.let { sb.append("\n  $it") }
                    sb.append("\n")
                }
                sb.andMore(rows.size, 20)
            }
        }

        if (sb.length <= headerOnly && !reached.any) {
            // Not one of the three answered, which is not the same as
            // all three answering with nothing.
            sb.setLength(0)
            sb.append("None of the imagery sources could be reached for ")
            sb.append("%.5f, %.5f ".format(Locale.US, lat, lon))
            sb.append("(Wikimedia Commons, KartaView, Panoramax), so nothing ")
            sb.append("is claimed about what has been photographed there.\n")
            return@runCatching sb.toString()
        }

        if (sb.length <= headerOnly) {
            // Rewritten, not appended to.
            //
            // The classifier reads the first non-blank line and
            // nothing else, so a header saying "Imagery near 51.507,
            // -0.127" with the absence underneath was filed as a
            // finding: a coordinate with no photographs of it
            // anywhere appeared in the dossier as a source that
            // returned something.
            sb.setLength(0)
            sb.append("No geotagged imagery found near ")
            sb.append("%.5f, %.5f.\n".format(Locale.US, lat, lon))
            sb.append("Wider options:\n")
            sb.append("Google Street View: https://www.google.com/maps/@$lat,$lon,17z\n")
            // Mapillary is listed last and labelled, because it is the
            // one entry here that frequently does not open: a WebGL
            // application that downloads a scene before it paints, on a
            // connection that is usually a phone's. Its API needs a key,
            // so this app cannot fetch on its behalf and cannot make it
            // any lighter — saying so is better than letting a blank
            // screen read as "nothing is here".
            sb.append("Mapillary — heavy WebGL page, often slow or blank on a ")
            sb.append("phone; open it on a desktop if it will not load:\n")
            sb.append("https://www.mapillary.com/app/?lat=$lat&lng=$lon&z=17\n")
        }
        sb.toString()
    }

    // ------------------------------------------------------------------
    // Aircraft and wallets
    // ------------------------------------------------------------------

    /** Aircraft lookup by Mode-S hex or tail number: type, operator, owner,
     *  plus a live position if it is airborne right now. */
    fun aircraft(input: String): Result<String> = runCatching {
        val t = input.trim().uppercase().replace(" ", "")
        val sb = StringBuilder("Aircraft — $t\n")
        val headerOnly = sb.length
        var learned = false
        val hex = if (Regex("^[0-9A-F]{6}$").matches(t)) t else null
        var reg: String? = if (hex == null) t else null
        if (hex != null) {
            runCatching {
                val o = json.parseToJsonElement(get("https://hexdb.io/api/v1/aircraft/$hex")).jsonObject
                reg = o.str("Registration")
                sb.append("Registration: ${o.str("Registration")}\n")
                sb.append("Type: ${o.str("Manufacturer")} ${o.str("Type")} (${o.str("ICAOTypeCode")})\n")
                o.str("RegisteredOwners")?.let { sb.append("Registered owner: $it\n") }
                o.str("OperatorFlagCode")?.let { sb.append("Operator code: $it\n") }
                learned = true
            }.onFailure { sb.append("hexdb could not be reached for Mode-S $hex.\n") }
        }
        // Live position from adsb.lol (keyless, open ADS-B aggregation).
        val path = if (hex != null) "icao/$hex" else "registration/${reg ?: t}"
        runCatching {
            val ac = json.parseToJsonElement(get("https://api.adsb.lol/v2/$path")).jsonObject["ac"] as? JsonArray
            val a = ac?.firstOrNull() as? JsonObject
            if (a != null) {
                sb.append("\nLIVE — airborne now:\n")
                a.str("flight")?.trim()?.takeIf { it.isNotBlank() }?.let { sb.append("Callsign: $it\n") }
                val la = a.dbl("lat"); val lo = a.dbl("lon")
                if (la != null && lo != null) {
                    sb.append("Position: %.4f, %.4f\n".format(Locale.US, la, lo))
                    com.osint.unified.domain.Mgrs.from(la, lo)?.let { sb.append("MGRS: $it\n") }
                    sb.append("Map: https://globe.adsb.lol/?icao=${hex ?: ""}&zoom=9&lat=$la&lon=$lo\n")
                }
                a.dbl("alt_baro")?.let { sb.append("Altitude: ${it.toInt()} ft\n") }
                a.dbl("gs")?.let { sb.append("Ground speed: ${it.toInt()} kt\n") }
                a.dbl("track")?.let { sb.append("Track: ${it.toInt()}°\n") }
                a.str("r")?.let { sb.append("Reg: $it\n") }
                a.str("t")?.let { sb.append("Type: $it\n") }
                learned = true
            } else {
                sb.append("\nNot currently transmitting ADS-B.\n")
            }
        }
        if (!learned) {
            // Neither register nor live feed told us anything, so the
            // header is the whole of it — and a header reads as a
            // finding. Which of the two it was is already in the
            // buffer; this only puts a line the classifier can read
            // in front of it.
            val lines = sb.substring(headerOnly).trim()
            val reachable = !lines.contains("could not be reached")
            return@runCatching buildString {
                if (reachable) {
                    append("No registration or live track found for $t.\n")
                } else {
                    append("The aircraft registers could not be reached for $t, ")
                    append("so nothing is claimed either way.\n")
                }
                if (lines.isNotBlank()) append(lines).append('\n')
                if (reg != null) {
                    append("\nHistory: https://globe.adsb.lol/?reg=${seg(reg ?: "")}\n")
                }
            }
        }
        if (reg != null) {
            sb.append("\nHistory: https://globe.adsb.lol/?reg=${seg(reg ?: "")}\n")
            sb.append("Photos: https://www.jetphotos.com/registration/${seg(reg ?: "")}\n")
        }
        sb.toString()
    }

    /** Bitcoin / Ethereum address intelligence from keyless explorers. */
    fun wallet(address: String): Result<String> = runCatching {
        val a = address.trim()
        when {
            a.startsWith("0x") && a.length == 42 -> ethWallet(a)
            Regex("^(bc1|[13])[a-zA-HJ-NP-Z0-9]{25,62}$").matches(a) -> btcWallet(a)
            else -> "Not a recognised BTC or ETH address: $a"
        }
    }

    private fun btcWallet(a: String): String {
        val o = json.parseToJsonElement(get("https://blockchain.info/rawaddr/$a?limit=10")).jsonObject
        val sb = StringBuilder("Bitcoin address — $a\n")
        val sat = 100_000_000.0
        o.dbl("final_balance")?.let { sb.append("Balance: %.8f BTC\n".format(Locale.US, it / sat)) }
        o.dbl("total_received")?.let { sb.append("Total received: %.8f BTC\n".format(Locale.US, it / sat)) }
        o.dbl("total_sent")?.let { sb.append("Total sent: %.8f BTC\n".format(Locale.US, it / sat)) }
        o.int("n_tx")?.let { sb.append("Transactions: $it\n") }
        val txs = o["txs"] as? JsonArray
        val counterparties = LinkedHashSet<String>()
        /** Addresses this wallet spends from, so its own change is not a payee. */
        val own = LinkedHashSet<String>()
        if (!txs.isNullOrEmpty()) {
            sb.append("\nRecent transactions:\n")
            txs.take(10).forEach { t ->
                val tx = t as? JsonObject ?: return@forEach
                val time = tx.dbl("time")?.let { utc(it.toLong() * 1000) } ?: "?"
                sb.append("• $time  ${tx.str("hash")?.take(20)}…\n")
                // Outputs that are not this address, minus the ones
                // this address also spends from — a wallet's own
                // change addresses were being printed as people it
                // had paid. Collected from the inputs of the same
                // ten transactions, which is the cheapest evidence
                // available that an address belongs to the same
                // wallet.
                (tx["out"] as? JsonArray)?.forEach { out ->
                    (out as? JsonObject)?.str("addr")?.takeIf { it != a }?.let { counterparties.add(it) }
                }
                (tx["inputs"] as? JsonArray)?.forEach { inp ->
                    ((inp as? JsonObject)?.get("prev_out") as? JsonObject)
                        .str("addr")?.let { own.add(it) }
                }
            }
        }
        val payees = counterparties - own
        if (payees.isNotEmpty()) {
            sb.append("\nCounterparty addresses (${payees.size}")
            if (payees.size > 20) sb.append(", first 20 shown")
            sb.append("):\n")
            payees.take(20).forEach { sb.append("• $it\n") }
        }
        if (o.int("n_tx") == 0 || (txs.isNullOrEmpty() && o.dbl("total_received") == 0.0)) {
            // Same reason as the Ethereum branch: an address nobody
            // has ever paid is an absence, and it was being reported
            // under a header that reads as a finding.
            return buildString {
                append("No transactions recorded against the Bitcoin address $a.\n")
                append("\nExplorer: https://www.blockchain.com/btc/address/$a")
            }
        }
        sb.append("\nExplorer: https://www.blockchain.com/btc/address/$a")
        sb.append("\nGraph: https://oxt.me/address/$a")
        return sb.toString()
    }

    /**
     * An Ethereum address, read straight off the chain.
     *
     * This used Ethplorer with `apiKey=freekey`, which is a key —
     * Ethplorer's shared demo one, rate limited across everybody who
     * ever copied it out of a tutorial. This app's rule is no keys
     * and no accounts, and a key everyone shares is still a key, and
     * in practice a mostly rate-limited one.
     *
     * A public JSON-RPC node answers the same two questions with no
     * key at all: the balance and how many transactions the address
     * has sent. Token holdings are lost with Ethplorer, which is a
     * real reduction and the honest price of not using a key. Two
     * nodes, because one being down should not take the tool with it.
     */
    private fun ethWallet(a: String): String {
        val sb = StringBuilder("Ethereum address — $a\n")
        val balance = ethRpc(a, "eth_getBalance")
        val nonce = ethRpc(a, "eth_getTransactionCount")
        if (balance != null) {
            // Wei to ether, in a BigDecimal, because a balance does not
            // survive the trip through a Double.
            val eth = java.math.BigDecimal(balance)
                .divide(java.math.BigDecimal("1000000000000000000"))
            sb.append("Balance: ${eth.toPlainString().take(24)} ETH\n")
        }
        if (nonce != null) sb.append("Transactions sent: $nonce\n")
        if (balance == null && nonce == null) {
            // In front of the header, not under it. The classifier
            // reads the first non-blank line, so "Ethereum address —
            // 0x…" followed by "no public node answered" was filed as
            // a finding — and this tool is graded A, so a request that
            // reached nobody was presented as a reliable one that did.
            return buildString {
                append("No public node answered for $a, so nothing is known ")
                append("about this address.\n")
                append("\nExplorer: https://etherscan.io/address/$a")
            }
        }
        sb.append("\nBalance and nonce only — reading token holdings needs a keyed ")
        sb.append("service, and this app does not use keys.")
        sb.append("\nExplorer: https://etherscan.io/address/$a")
        sb.append("\nLabels: https://etherscan.io/address/$a#analytics")
        return sb.toString()
    }

    /** One JSON-RPC call against whichever public node answers first. */
    private fun ethRpc(address: String, method: String): java.math.BigInteger? {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"$method",""" +
            """"params":["$address","latest"]}"""
        ETH_NODES.forEach { node ->
            val hex = runCatching {
                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(node)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    json.parseToJsonElement(resp.body?.string().orEmpty())
                        .jsonObject["result"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
            if (hex != null && hex.startsWith("0x")) {
                runCatching { java.math.BigInteger(hex.removePrefix("0x"), 16) }
                    .getOrNull()?.let { return it }
            }
        }
        return null
    }

    /** Public Ethereum RPC endpoints — no key, no account. */
    private val ETH_NODES = listOf(
        "https://cloudflare-eth.com",
        "https://eth.llamarpc.com",
        "https://rpc.ankr.com/eth"
    )

    /**
     * Generates the address patterns an organisation is likely to use for a
     * person, then checks which of them the mail server will actually accept
     * on the evidence available without sending mail: whether the domain has
     * MX at all, and whether a catch-all is in play.
     *
     * Input is "First Last @domain.com" or "First Last domain.com".
     */
    fun emailPermutations(input: String): Result<String> = runCatching {
        val raw = input.trim()
        // A bounded character class, not a repeated group: the
        // group form recurses once per label in java.util.regex and
        // overflows the stack on a long enough paste. See
        // the Hostnames file, whose check is then used to confirm
        // that the thing found really is a host.
        val tail = Regex("""[@\s]([a-z0-9.\-]{1,253})\s*$""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.lowercase()
            ?.takeIf { Hostnames.isHostname(it) }
        val domain = tail
            ?: return@runCatching "Give a name and a domain, e.g. 'Jane Doe @example.com'."
        // Cut at where the domain actually is, not at a
        // case-sensitive match for its lowercased form.
        //
        // domain is lowercased above, raw is whatever was typed —
        // so "Jane Doe @Example.COM" found no match, the whole
        // string stayed, and the last name became "@example.com".
        // Every permutation it then generated was built from that.
        val cut = raw.lastIndexOf(domain, ignoreCase = true)
        val nameParts = (if (cut > 0) raw.substring(0, cut) else raw)
            .trim().trimEnd('@').trim()
            .split(Regex("""\s+""")).filter { it.isNotBlank() }.map { it.lowercase() }
        if (nameParts.size < 2) {
            return@runCatching "Give at least a first and last name, e.g. 'Jane Doe @$domain'."
        }
        val first = nameParts.first()
        val last = nameParts.last()
        val middle = nameParts.drop(1).dropLast(1).firstOrNull()
        val f = first.first()
        val l = last.first()
        val m = middle?.first()

        val patterns = linkedSetOf(
            "$first.$last", "$first$last", "$f$last", "$first$l", "$f.$last",
            "$last.$first", "$last$first", "$last$f", "$first-$last", "${first}_${last}",
            first, last, "$f$l", "$first.$l", "$f.$l"
        )
        if (m != null) {
            patterns.add("$f$m$last"); patterns.add("$first.$middle.$last"); patterns.add("$f$m$l")
        }

        val sb = StringBuilder("Likely addresses — ${nameParts.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }} at $domain\n")

        // What the domain's mail setup says about deliverability.
        val mx = runCatching {
            val o = json.parseToJsonElement(get("https://dns.google/resolve?name=$domain&type=MX")).jsonObject
            (o["Answer"] as? JsonArray)?.mapNotNull { (it as? JsonObject).str("data") } ?: emptyList()
        }.getOrNull()
        if (mx == null) {
            // Not the same as no MX record, which this used to print
            // as a warning that the domain may not receive mail.
            sb.append("\nThe DNS resolver did not answer, so nothing is known ")
            sb.append("about where this domain's mail goes.\n")
        } else if (mx.isEmpty()) {
            sb.append("\nWARNING: $domain publishes no MX record, so it may not receive mail at all.\n")
        } else {
            sb.append("\nMail handled by: ${mx.take(3).joinToString(", ") { it.substringAfter(" ").trimEnd('.') }}\n")
            val provider = when {
                mx.any { it.contains("google", true) } -> "Google Workspace"
                mx.any { it.contains("outlook", true) || it.contains("protection.outlook", true) } -> "Microsoft 365"
                mx.any { it.contains("zoho", true) } -> "Zoho"
                mx.any { it.contains("protonmail", true) || it.contains("proton.me", true) } -> "Proton"
                else -> null
            }
            provider?.let { sb.append("Provider: $it\n") }
        }

        sb.append("\n${patterns.size} candidate addresses, commonest first:\n")
        patterns.forEach { sb.append("• $it@$domain\n") }
        sb.append("\nThese are generated patterns, not confirmed accounts. ")
        sb.append("Run the breach check or the account finder against the ones that matter — ")
        sb.append("a hit there is evidence the address is real.")
        sb.toString()
    }

    /**
     * Typosquat sweep: generates the lookalike domains an attacker would
     * register, then resolves each one. Only domains that actually exist are
     * reported, with their address and mail setup.
     */
    suspend fun typosquat(input: String): String = coroutineScope {
        val domain = input.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").substringBefore("/").removePrefix("www.")
        val name = domain.substringBefore(".")
        val tld = domain.substringAfter(".")
        if (name.length < 3) return@coroutineScope "Domain too short to permute."

        val keyboard = mapOf(
            'q' to "wa", 'w' to "qes", 'e' to "wrd", 'r' to "etf", 't' to "ryg", 'y' to "tuh",
            'u' to "yij", 'i' to "uok", 'o' to "ipl", 'p' to "ol", 'a' to "qsz", 's' to "awdx",
            'd' to "serfc", 'f' to "drtgv", 'g' to "ftyhb", 'h' to "gyujn", 'j' to "huikm",
            'k' to "jiol", 'l' to "kop", 'z' to "asx", 'x' to "zsdc", 'c' to "xdfv",
            'v' to "cfgb", 'b' to "vghn", 'n' to "bhjm", 'm' to "njk"
        )
        val variants = LinkedHashSet<String>()
        // Character omission, duplication, transposition and fat-finger swaps.
        for (i in name.indices) {
            variants.add(name.removeRange(i, i + 1))
            variants.add(name.substring(0, i) + name[i] + name.substring(i))
            if (i < name.length - 1) {
                val chars = name.toCharArray()
                val t = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = t
                variants.add(String(chars))
            }
            keyboard[name[i]]?.forEach { k ->
                variants.add(name.substring(0, i) + k + name.substring(i + 1))
            }
        }
        // Homoglyphs and common additions.
        variants.add(name.replace("o", "0")); variants.add(name.replace("l", "1"))
        variants.add(name.replace("i", "1")); variants.add(name.replace("e", "3"))
        variants.add(name.replace("rn", "m")); variants.add(name.replace("m", "rn"))
        listOf("-secure", "-login", "-support", "-mail", "secure", "login", "app", "s").forEach {
            variants.add("$name$it"); variants.add("$it$name")
        }
        variants.remove(name)

        val otherTlds = listOf("com", "net", "org", "co", "info", "online", "site", "xyz", "io", "app")
            .filter { it != tld }.map { "$name.$it" }
        val candidates = (variants.map { "$it.$tld" } + otherTlds).distinct().take(160)

        // A domain search already runs ~20 tools in parallel; this one would
        // otherwise add 160 simultaneous DNS lookups on a phone radio, so it
        // is bounded rather than let loose.
        val gate = Semaphore(24)
        val live = candidates.map { cand ->
            async {
                val a = runCatching {
                    gate.withPermit {
                        val o = json.parseToJsonElement(
                            get("https://dns.google/resolve?type=A&name=$cand")
                        ).jsonObject
                        // SERVFAIL and REFUSED arrive as HTTP 200 with
                        // no Answer, and were being counted as "this
                        // lookalike does not resolve" — the reassuring
                        // half of a split this function exists to
                        // make. NXDOMAIN (3) really is "no such name".
                        val status = (o["Status"] as? JsonPrimitive)?.content?.toIntOrNull()
                        if (status != null && status != 0 && status != 3) {
                            throw IOException("resolver status $status")
                        }
                        (o["Answer"] as? JsonArray)?.mapNotNull { (it as? JsonObject).str("data") }
                            ?.filter { IPV4_ONLY.matches(it) } ?: emptyList()
                    }
                }.getOrNull()
                when {
                    a == null -> cand to null
                    a.isEmpty() -> null
                    else -> cand to a.first()
                }
            }
        }.mapNotNull { it.await() }

        // Split, because "did not resolve" and "could not be asked"
        // are the difference between reassurance and nothing at all.
        // This counted every failed lookup as a lookalike that does
        // not exist, and then said all hundred and sixty had been
        // "generated and resolved" having resolved none of them.
        val asked = live.filter { it.second != null }
        val notAsked = live.count { it.second == null }
        val answered = candidates.size - notAsked

        if (answered == 0) {
            return@coroutineScope "Typosquat sweep could not be reached for $domain: " +
                "no lookalike could be resolved, so this says nothing either way."
        }

        buildString {
            append("Typosquat sweep — $domain\n")
            append("${candidates.size} lookalikes generated, $answered resolved; ")
            append("${asked.size} are registered and live.\n")
            if (notAsked > 0) {
                append("$notAsked could not be looked up and are not counted either way.\n")
            }
            if (asked.isEmpty()) {
                append("\nNone of the obvious lookalikes resolve. That is unusual for a well-known brand ")
                append("and good news for this one.")
            } else {
                append("\nRegistered lookalikes:\n")
                asked.forEach { (d, ip) -> append("• $d → $ip\n") }
                append("\nA live lookalike is not proof of malice — brands register their own ")
                append("defensively. Check each one's registration date and TLS certificate ")
                append("to tell a defensive registration from a phishing host.")
            }
        }
    }

    // ------------------------------------------------------------------
    // Geospatial analysis
    // ------------------------------------------------------------------

    /** Chronolocation helper: terrain elevation, solar position now, shadow
     *  direction and length ratio, sunrise/sunset. Used to test whether a
     *  photo's shadows match a claimed place and time. */
    fun sunAndTerrain(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        val sb = StringBuilder("Terrain & sun — %.5f, %.5f\n".format(Locale.US, lat, lon))
        runCatching {
            val o = json.parseToJsonElement(
                get("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon")
            ).jsonObject
            ((o["elevation"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull
                ?.let { sb.append("Ground elevation: ${it.toInt()} m\n") }
        }
        com.osint.unified.domain.Mgrs.from(lat, lon)?.let { sb.append("MGRS: $it\n") }

        // The moment asked about, or this one if none was given.
        val asked = parseWhen(input)
        val now = Date(asked?.instant ?: System.currentTimeMillis())
        val sun = solarPosition(lat, lon, now)
        if (asked == null) {
            if (datePresent(input)) {
                sb.append("\nThe date after the coordinate could not be read — a ")
                sb.append("month must be 01-12 and a day a real day of it — so ")
                sb.append("this is now, not the moment you meant.\n")
            }
            sb.append("\nSun right now (${utc(now.time)}):\n")
        } else {
            sb.append("\nSun at ${utc(now.time)}")
            sb.append(" — the moment you gave")
            if (!asked.hasTime) sb.append(", midnight UTC because no time was given")
            else if (!asked.asGiven.contains('Z') && !asked.asGiven.contains('+')) {
                sb.append(", read as UTC because no zone was given")
            }
            sb.append(":\n")
        }
        sb.append("Elevation: %.1f°\n".format(Locale.US, sun.first))
        sb.append("Azimuth: %.1f° (compass bearing to the sun)\n".format(Locale.US, sun.second))
        if (sun.first > 0) {
            val shadowBearing = (sun.second + 180.0) % 360.0
            // The cotangent runs away near the horizon.
            //
            // At an elevation of 0.1 degrees the ratio is 573, and
            // at 0.01 it is 5,730 — printed to two decimal places
            // as though it were a measurement. Within a few degrees
            // of the horizon the number is also meaningless in
            // practice: terrain, refraction and the length of the
            // shadow itself all swamp it. So it is given as a bound
            // rather than a figure.
            if (sun.first < 3.0) {
                sb.append(
                    "The sun is %.1f° above the horizon, so shadows fall toward "
                        .format(Locale.US, sun.first)
                )
                sb.append("%.0f° and are more than twenty times the object's "
                    .format(Locale.US, shadowBearing))
                sb.append("height — too close to the horizon for the ratio to ")
                sb.append("mean anything, because terrain and refraction ")
                sb.append("dominate it.\n")
            } else {
                val ratio = 1.0 / tan(Math.toRadians(sun.first))
                sb.append("Shadows fall toward %.0f° and are %.2f× the object's height.\n".format(Locale.US, shadowBearing, ratio))
            }
        } else {
            sb.append("The sun is below the horizon here — it is night.\n")
        }

        // What the clock says at that place.
        //
        // Chronolocation is matching a timestamp to a place and
        // every number above this line is UTC, so the one piece the
        // reader has to supply themselves was the offset. Nominatim
        // does not return a timezone and nothing else here did
        // either; Open-Meteo resolves it from the coordinate, on a
        // client this function already uses.
        runCatching {
            val o = json.parseToJsonElement(
                get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&timezone=auto&forecast_days=1")
            ).jsonObject
            val zone = (o["timezone"] as? JsonPrimitive)?.contentOrNull
            val offset = (o["utc_offset_seconds"] as? JsonPrimitive)?.doubleOrNull?.toLong()
            if (zone != null && offset != null) {
                val sign = if (offset < 0) "-" else "+"
                val abs = kotlin.math.abs(offset)
                sb.append("\nLocal time zone: $zone (UTC$sign%02d:%02d)\n"
                    .format(Locale.US, abs / 3600, (abs % 3600) / 60))
                sb.append(
                    if (asked == null) "Local time now: " else "Local time then: "
                )
                sb.append("${wallClock(now.time + offset * 1000)}\n")
                sb.append("Everything above this line is UTC; a timestamp on a ")
                sb.append("photograph taken here would read local.\n")
            }
        }

        // Sunrise, sunset and the weather on that day, for verifying a
        // claimed timestamp. The archive endpoint for a past date, the
        // forecast endpoint for today — the same fields either way, and
        // one request, not a multiplier.
        runCatching {
            val day = isoDay(now.time)
            // The archive runs about five days behind and has nothing
            // at all for the future. Asking it anyway printed
            // "sunrise null, sunset null" as a finding.
            val tooRecent = now.time > System.currentTimeMillis() - ARCHIVE_LAG_MS
            val url = if (asked == null || tooRecent) {
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min," +
                    "precipitation_sum&timezone=UTC&forecast_days=1"
            } else {
                "https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon" +
                    "&start_date=$day&end_date=$day" +
                    "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min," +
                    "precipitation_sum&timezone=UTC"
            }
            if (asked != null && tooRecent) {
                sb.append("\nThe weather archive runs about five days behind, so ")
                sb.append("$day is not in it yet; what follows is today.\n")
            }
            val o = json.parseToJsonElement(get(url)).jsonObject["daily"]?.jsonObject
            fun first(k: String) = ((o?.get(k) as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            sb.append(if (asked == null || tooRecent) "\nToday (UTC): " else "\n$day (UTC): ")
            sb.append("sunrise ${first("sunrise")}, sunset ${first("sunset")}\n")
            sb.append("Temp ${first("temperature_2m_min")}–${first("temperature_2m_max")}°C, ")
            sb.append("precipitation ${first("precipitation_sum")} mm\n")
            if (asked != null && sun.first > 3.0) {
                sb.append("\nSo: a photograph taken here at that moment has shadows ")
                sb.append("falling toward %.0f° at %.2f× the object's height. "
                    .format(Locale.US, (sun.second + 180.0) % 360.0,
                        1.0 / tan(Math.toRadians(sun.first))))
                sb.append("Shadows that disagree with that are evidence the time, ")
                sb.append("the place or both are wrong.\n")
            } else if (asked != null && sun.first <= 0.0) {
                sb.append("\nSo: the sun was below the horizon at that moment. ")
                sb.append("A daylight photograph is not from this time and place.\n")
            }
        }
        sb.append("\nSun path tool: https://www.suncalc.org/#/$lat,$lon,15/")
        sb.toString()
    }

    /** Historical weather at a coordinate — tests whether footage or a photo
     *  matches the conditions on the date it claims. */
    fun historicalWeather(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        // The week around the date asked about, or the last month if
        // none was given.
        //
        // The window was hardcoded to the last 31 days under a tool
        // description promising "tests whether a photo matches its
        // claimed date" — so a photograph could be confirmed or
        // contradicted by the weather on a completely different day,
        // with nothing in the output saying which day it was about.
        val asked = parseWhen(input)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        if (asked != null) cal.timeInMillis = asked.instant
        // The archive runs about five days behind, so a date inside
        // that window has no data and the neighbours are what there is.
        // The window ends before the archive's lag, never after it.
        //
        // It was the day plus or minus three, and the archive runs
        // about five days behind — so for any date in the last three
        // days every day in the window was empty and the output read
        // as "the archive records nothing here" rather than "this is
        // too recent to be in it". The end can also never be in the
        // future, which the endpoint refuses outright.
        val latest = System.currentTimeMillis() - ARCHIVE_LAG_MS
        if (asked != null && asked.instant > latest) {
            return@runCatching "The weather archive runs about five days " +
                "behind, so ${isoDay(asked.instant)} is not in it yet. Ask " +
                "again in a few days, or use the forecast the sun and terrain " +
                "tool prints for today."
        }
        cal.add(Calendar.DAY_OF_YEAR, if (asked != null) -3 else -31)
        val start = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, if (asked != null) 6 else 24)
        if (cal.timeInMillis > latest) cal.timeInMillis = latest
        val end = fmt.format(cal.time)
        val o = json.parseToJsonElement(
            get("https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon" +
                "&start_date=$start&end_date=$end&daily=temperature_2m_max,temperature_2m_min," +
                "precipitation_sum,snowfall_sum,weathercode&timezone=UTC")
        ).jsonObject["daily"]?.jsonObject ?: return@runCatching "No archive data for that point."
        fun col(k: String) = (o[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "-" } ?: emptyList()
        val days = col("time"); val tmax = col("temperature_2m_max"); val tmin = col("temperature_2m_min")
        val rain = col("precipitation_sum"); val snow = col("snowfall_sum"); val code = col("weathercode")
        val sb = StringBuilder("Historical weather — %.4f, %.4f\n".format(Locale.US, lat, lon))
        if (asked != null) {
            sb.append("The days around ${isoDay(asked.instant)}, which is the date you gave")
            sb.append(" — $start to $end (UTC)\n\n")
        } else if (datePresent(input)) {
            // Written and refused, which is not the same as absent.
            // The app answered a different question and said it had
            // never been asked the first one.
            sb.append("The date after the coordinate could not be read — a ")
            sb.append("month must be 01-12 and a day a real day of it. This ")
            sb.append("is the last month instead: $start to $end (UTC).\n\n")
        } else {
            sb.append("$start to $end (UTC). No date was given, so this is the ")
            sb.append("last month rather than any claimed day: add one after the ")
            sb.append("coordinate, as `51.5, -0.12, 2026-03-02`.\n\n")
        }
        days.indices.forEach { i ->
            sb.append("${days[i]}  ${tmin.getOrNull(i)}–${tmax.getOrNull(i)}°C  ")
            sb.append("rain ${rain.getOrNull(i)}mm  snow ${snow.getOrNull(i)}cm  ")
            sb.append(wmo(code.getOrNull(i)?.toDoubleOrNull()?.toInt()))
            sb.append("\n")
        }
        sb.append(
            if (asked != null) {
                "\nCompare this with the weather in the photograph. Snow on the " +
                    "ground on a day the archive records as dry and 18°C is a " +
                    "contradiction worth following."
            } else {
                "\nUse this to check whether a photo's weather matches its " +
                    "claimed date — give the date to be told about that day."
            }
        )
        sb.toString()
    }

    /** UK street-level crime within a mile of a coordinate (data.police.uk). */
    fun ukCrime(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        val arr = json.parseToJsonElement(
            get("https://data.police.uk/api/crimes-street/all-crime?lat=$lat&lng=$lon")
        ) as? JsonArray
            // A body that is not an array is data.police.uk declining
            // — it answers an object with a `status` and a message
            // for a rate limit or a bad month — and that was being
            // printed as "no crime data for that point", which reads
            // as a fact about the place.
            ?: throw java.io.IOException(
                "data.police.uk answered in an unexpected shape"
            )
        if (arr.isEmpty()) return@runCatching "UK crime data: nothing reported near %.4f, %.4f (or outside the UK).".format(Locale.US, lat, lon)
        val byCat = LinkedHashMap<String, Int>()
        val byStreet = LinkedHashMap<String, Int>()
        var month = ""
        arr.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            o.str("category")?.let { byCat[it] = (byCat[it] ?: 0) + 1 }
            ((o["location"] as? JsonObject)?.get("street") as? JsonObject)?.str("name")
                ?.let { byStreet[it] = (byStreet[it] ?: 0) + 1 }
            o.str("month")?.let { month = it }
        }
        val sb = StringBuilder("UK street crime — %.4f, %.4f\n".format(Locale.US, lat, lon))
        sb.append("${arr.size} offences reported in $month within ~1 mile\n\nBy category:\n")
        byCat.entries.sortedByDescending { it.value }.forEach {
            sb.append("• ${it.key.replace('-', ' ')}: ${it.value}\n")
        }
        sb.append("\nHotspot streets:\n")
        byStreet.entries.sortedByDescending { it.value }.take(15).forEach {
            sb.append("• ${it.key}: ${it.value}\n")
        }
        sb.toString()
    }

    // ------------------------------------------------------------------
    // Vulnerability context
    // ------------------------------------------------------------------

    /** CVE context for software found on a host: description and exploit
     *  likelihood (FIRST EPSS + CIRCL CVE database, both keyless). */
    fun cveLookup(input: String): Result<String> = runCatching {
        val t = input.trim().uppercase()
        if (Regex("^CVE-\\d{4}-\\d{4,}$").matches(t)) {
            val sb = StringBuilder("$t\n")
            runCatching {
                val o = json.parseToJsonElement(get("https://cve.circl.lu/api/vulnerability/$t")).jsonObject
                val cna = ((o["containers"] as? JsonObject)?.get("cna") as? JsonObject)
                cna.str("title")?.let { sb.append("\n$it\n") }
                ((cna?.get("descriptions") as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.str("value")?.let { sb.append("\n$it\n") }
                ((o["cveMetadata"] as? JsonObject)).str("datePublished")
                    ?.let { sb.append("\nPublished: ${it.substringBefore('T')}") }
            }
            runCatching {
                val d = (json.parseToJsonElement(get("https://api.first.org/data/v1/epss?cve=$t"))
                    .jsonObject["data"] as? JsonArray)?.firstOrNull()?.jsonObject
                d?.str("epss")?.let { e ->
                    val pct = (e.toDoubleOrNull() ?: 0.0) * 100
                    sb.append("\nEPSS: %.2f%% chance of exploitation in the next 30 days".format(Locale.US, pct))
                }
                d?.str("percentile")?.let {
                    sb.append(" — more likely than %.1f%% of all CVEs".format(Locale.US, (it.toDoubleOrNull() ?: 0.0) * 100))
                }
            }
            sb.append("\n\nhttps://nvd.nist.gov/vuln/detail/$t")
            sb.append("\nExploits: https://www.exploit-db.com/search?cve=${t.removePrefix("CVE-")}")
            return@runCatching sb.toString()
        }
        // Not a CVE id — treat it as vendor/product. CIRCL returns
        // {"results": {"nvd": [[id, record], …]}}.
        val parts = t.lowercase().split("/", " ").filter { it.isNotBlank() }
        val vendor = parts.getOrNull(0) ?: return@runCatching "Give a CVE id or 'vendor product'."
        val product = parts.getOrNull(1) ?: vendor
        val results = (json.parseToJsonElement(get("https://cve.circl.lu/api/search/$vendor/$product"))
            .jsonObject["results"] as? JsonObject)
            // Missing is not empty. The comment below is right that
            // an empty `results` is a real absence; a `results` that
            // is not there at all is the index answering something
            // this code does not understand.
            ?: throw java.io.IOException("the CVE index returned no results block")
        val sb = StringBuilder("CVEs for $vendor $product\n")
        // No Reached() here: the request above is not wrapped, so
        // reaching this line means the index answered and an empty
        // result really is an absence.
        val headerOnly = sb.length
        results.forEach { (source, arr) ->
            val rows = arr as? JsonArray ?: return@forEach
            if (rows.isEmpty()) return@forEach
            sb.append("\n${source.uppercase()} — ${rows.size} records:\n")
            rows.take(25).forEach { row ->
                val pair = row as? JsonArray ?: return@forEach
                val id = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                val rec = pair.getOrNull(1) as? JsonObject
                val cna = ((rec?.get("containers") as? JsonObject)?.get("cna") as? JsonObject)
                val title = cna.str("title")
                    ?: ((cna?.get("descriptions") as? JsonArray)?.firstOrNull() as? JsonObject)?.str("value")
                sb.append("• ${id?.uppercase()}")
                if (!title.isNullOrBlank()) sb.append(" — ${title.take(150)}")
                sb.append("\n")
            }
        }
        if (sb.length <= headerOnly) "No CVEs found for $vendor $product."
        else sb.toString()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Percent-encodes a value for use inside a displayed URL path. A raw
     *  space in a link makes it unopenable, and names routinely contain them. */
    private fun seg(v: String): String =
        URLEncoder.encode(v.trim(), "UTF-8").replace("+", "%20")

    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.int(k: String) = (this?.get(k) as? JsonPrimitive)?.intOrNull
    private fun JsonObject?.dbl(k: String) = (this?.get(k) as? JsonPrimitive)?.doubleOrNull

    /** Every string-valued claim for a Wikidata property. */
    private fun JsonObject?.stringValues(prop: String): List<String> {
        val arr = this?.get(prop) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { st ->
            (((st as? JsonObject)?.get("mainsnak") as? JsonObject)
                ?.get("datavalue") as? JsonObject)?.str("value")
        }
    }

    /** Every entity (Q-id) claim for a Wikidata property. */
    private fun JsonObject?.entityIds(prop: String): List<String> {
        val arr = this?.get(prop) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { st ->
            val dv = ((st as? JsonObject)?.get("mainsnak") as? JsonObject)?.get("datavalue") as? JsonObject
            (dv?.get("value") as? JsonObject)?.str("id")
        }
    }

    private fun timeValue(claims: JsonObject?, prop: String): String? {
        val arr = claims?.get(prop) as? JsonArray ?: return null
        val dv = ((arr.firstOrNull() as? JsonObject)?.get("mainsnak") as? JsonObject)
            ?.get("datavalue") as? JsonObject
        val t = (dv?.get("value") as? JsonObject)?.str("time") ?: return null
        return t.removePrefix("+").substringBefore("T")
    }

    /** See Coordinates: there were seven of these and six of them
     *  did not know about the date the other one had learned. */
    private fun parseCoord(input: String): Pair<Double, Double>? =
        com.osint.unified.domain.Coordinates.parse(input)?.let { it.lat to it.lon }

    /** A moment given after the coordinate, and whether it named a time. */
    internal data class Moment(val instant: Long, val hasTime: Boolean, val asGiven: String)

    /**
     * The date, or date and time, written after a coordinate.
     *
     * Both chronolocation tools exist to test whether a photograph
     * matches a claimed time and place, and neither could be told the
     * time: sunAndTerrain computed the solar position for the instant
     * the analyst happened to be reading, and historicalWeather
     * hardcoded a window of the last month. Meanwhile the same dossier
     * routinely holds the EXIF capture timestamp twenty centimetres up
     * the screen. parseCoord read the first two comma-separated fields
     * and silently dropped the rest, so there was nowhere to put it.
     *
     * Accepted: `51.5,-0.12, 2026-03-02` and
     * `51.5,-0.12, 2026-03-02 14:30` — with T or a space, optional
     * seconds, and an optional Z or ±hh:mm. A time with no zone is
     * read as UTC and the output says so, because a photograph's
     * timestamp usually is not UTC and guessing would be worse than
     * stating the assumption.
     */
    /** Whether something date-shaped was written at all. */
    internal fun datePresent(input: String): Boolean =
        WHEN_PATTERN.containsMatchIn(input)

    internal fun parseWhen(input: String): Moment? {
        val m = WHEN_PATTERN.find(input) ?: return null
        val (y, mo, d) = listOf(1, 2, 3).map { m.groupValues[it].toInt() }
        val hasTime = m.groupValues[4].isNotEmpty()
        val hh = m.groupValues[4].toIntOrNull() ?: 0
        val mi = m.groupValues[5].toIntOrNull() ?: 0
        val ss = m.groupValues[6].toIntOrNull() ?: 0
        if (mo !in 1..12 || d !in 1..31 || hh !in 0..23 || mi !in 0..59 || ss !in 0..60) {
            return null
        }
        val zone = m.groupValues[7]
        val offsetSeconds = when {
            zone.isEmpty() || zone.equals("Z", true) -> 0
            else -> {
                val sign = if (zone.startsWith("-")) -1 else 1
                val digits = zone.drop(1).replace(":", "")
                if (digits.length < 4) return null
                sign * (digits.take(2).toInt() * 3600 + digits.drop(2).take(2).toInt() * 60)
            }
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            // Strict, so 31 February is refused rather than rolled
            // forward to 3 March and then printed as "the moment you
            // gave".
            isLenient = false
            set(y, mo - 1, d, hh, mi, ss)
        }
        val millis = runCatching { cal.timeInMillis }.getOrNull() ?: return null
        return Moment(
            millis - offsetSeconds * 1000L,
            hasTime,
            m.value.trim()
        )
    }

    /** NOAA solar position: returns (elevation°, azimuth°) for a UTC instant. */
    private fun solarPosition(lat: Double, lon: Double, when_: Date): Pair<Double, Double> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = when_ }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0 + cal.get(Calendar.SECOND) / 3600.0

        // Julian day
        var y = year; var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = Math.floor(y / 100.0)
        val b = 2 - a + Math.floor(a / 4.0)
        val jd = Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + day + b - 1524.5 + hour / 24.0
        val n = jd - 2451545.0

        val meanLong = (280.460 + 0.9856474 * n).mod(360.0)
        val meanAnom = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0))
        val eclipLong = Math.toRadians(meanLong + 1.915 * sin(meanAnom) + 0.020 * sin(2 * meanAnom))
        val obliq = Math.toRadians(23.439 - 0.0000004 * n)

        val declination = asin(sin(obliq) * sin(eclipLong))
        var rightAsc = Math.toDegrees(atan2(cos(obliq) * sin(eclipLong), cos(eclipLong))).mod(360.0)

        // The day number at 0h UT, which is what this term wants —
        // not floor(n).
        //
        // `n` already carries the time of day, and a Julian day
        // starts at noon, so at midnight UT it ends in .5 and
        // flooring it lands on the wrong side of the boundary for
        // half of every day. The azimuth came out about a degree off,
        // with the error changing sign at noon: for London on the
        // solstice this read 179.88° at 12:00Z where NOAA says
        // 178.88°. A degree of shadow bearing is the width of the
        // claim a chronolocation is making.
        val dayNumber = n - hour / 24.0
        val gmst = (6.697374558 + 0.06570982441908 * dayNumber + 1.00273790935 * hour).mod(24.0)
        val lmst = (gmst * 15.0 + lon).mod(360.0)
        var hourAngle = Math.toRadians((lmst - rightAsc).mod(360.0))
        if (hourAngle > Math.PI) hourAngle -= 2 * Math.PI

        val latR = Math.toRadians(lat)
        val elevation = asin(sin(latR) * sin(declination) + cos(latR) * cos(declination) * cos(hourAngle))
        var azimuth = Math.toDegrees(
            atan2(-sin(hourAngle), tan(declination) * cos(latR) - sin(latR) * cos(hourAngle))
        ).mod(360.0)
        return Math.toDegrees(elevation) to azimuth
    }

    private fun wmo(code: Int?): String = when (code) {
        null -> ""
        0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "fog"; 51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"; 80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"; 95 -> "thunderstorm"; 96, 99 -> "thunderstorm w/ hail"
        else -> "code $code"
    }

    private fun sleepWindow(hours: IntArray): String {
        // The six consecutive UTC hours with the least activity.
        var best = 0; var bestSum = Int.MAX_VALUE
        for (s in 0 until 24) {
            var sum = 0
            for (k in 0 until 6) sum += hours[(s + k) % 24]
            if (sum < bestSum) { bestSum = sum; best = s }
        }
        return "${best}:00–${(best + 6) % 24}:00 UTC"
    }

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
            .get(Calendar.HOUR_OF_DAY)

    private fun isoHour(ts: String): Int? =
        ts.substringAfter("T", "").substringBefore(":").toIntOrNull()

    /** The same instant with the offset already added, so the
     *  label must not say UTC. */
    private fun wallClock(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    /** The UTC calendar day of an instant, as the APIs want it. */
    private fun isoDay(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private fun utc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private fun stripTags(s: String) = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")

    private fun emailsIn(s: String): List<String> =
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").findAll(s)
            .map { it.value }.distinct().toList()

    /**
     * A run of sub-lookups, and whether any of them answered.
     *
     * Several tools here ask three or four endpoints, wrap each in a
     * bare `runCatching`, and then conclude from an empty buffer that
     * the SOURCE had nothing — so airplane mode mid-lookup came out
     * as "OTX has nothing on it", which is the most assertive false
     * absence a tool can produce. An empty buffer means one of two
     * things and the difference is the whole point of this app.
     */
    private class Reached {
        var any = false
            private set

        fun <T> of(block: () -> T): T? {
            val attempt = runCatching(block)
            attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (attempt.isSuccess) any = true
            return attempt.getOrNull()
        }

        /**
         * For a source that can refuse with an HTTP 200.
         *
         * [of] counts a request that did not throw, which is not the
         * same as a question that was answered. hackertarget replies
         * 200 with "API count exceeded - Increase Quota with
         * Membership" when the free daily tier is spent;
         * `hackertargetAnswered` was written to catch exactly that
         * and does, and then the surrounding `of` marked the source
         * as having answered anyway. All three of `hostIntel`'s
         * probes are hackertarget, so a spent quota — routine on a
         * tier shared across an IP — printed "Host intel: no data
         * returned for example.com", which `Findings` reads as an
         * established absence.
         *
         * So the block says whether it got an answer, and only that
         * counts.
         */
        fun answering(block: () -> Boolean) {
            val attempt = runCatching(block)
            attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (attempt.getOrDefault(false)) any = true
        }
    }

    /**
     * The status behind a failure from [get], when there was one.
     *
     * [get] reports a non-2xx as IOException("HTTP nnn"); anything
     * else — a socket error, a cancellation — has no code and is a
     * transport failure, which is the distinction the callers need.
     */
    private fun httpCode(e: Throwable): Int? = (e as? IOException)?.message
        ?.takeIf { it.startsWith("HTTP ") }?.removePrefix("HTTP ")?.trim()?.toIntOrNull()

    /** Compiled once: this was being rebuilt per DNS answer. */
    private val IPV4_ONLY = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")

    /** Statuses each platform uses to mean "no such user". */
    private val SAYS_NO_DEFAULT = setOf(404, 410)

    private val SAYS_NO = mapOf(
        // getProfile answers 400, not 404, for an actor it cannot find.
        "Bluesky" to setOf(400, 404, 410)
    )

    private fun get(url: String, agent: String = ua): String {
        // A 5xx from these services means "busy", not "no". The Wayback CDX
        // endpoint in particular sheds load with a 503 under no special
        // provocation, and one unlucky second was costing the whole lookup.
        var last: IOException? = null
        repeat(2) { attempt ->
            if (attempt > 0) {
                // Interruptible, so STOP actually stops. A plain
                // Thread.sleep ignores cancellation, and a cancelled
                // lookup sat here for most of a second and then made
                // its second request anyway.
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("cancelled")
                }
                try {
                    Thread.sleep(700)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("cancelled", e)
                }
            }
            val request = Request.Builder().url(url)
                .header("User-Agent", agentFor(url, agent))
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                .build()
            client.newCall(request).execute().use { resp ->
                // Bounded, like the other tool clients: the target
                // here is whatever was typed in.
                val body = resp.body?.bodyText().orEmpty()
                if (resp.isSuccessful) return body
                last = IOException("HTTP ${resp.code}")
                // A 4xx is an answer; only a 5xx is worth asking again.
                if (resp.code < 500) throw last!!
            }
        }
        throw last ?: IOException("request failed")
    }
    /** Metres between two places, for turning a bounding box into a circle. */
    private fun metresBetween(
        aLat: Double, aLon: Double, bLat: Double, bLon: Double
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(aLat)) * kotlin.math.cos(Math.toRadians(bLat)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h).coerceAtMost(1.0))
    }

    private companion object {
        /**
         * A date, optionally with a time, written after a coordinate.
         *
         * Deliberately strict: a four-digit year with dashes. A looser
         * pattern would read the "2026" out of a coordinate's decimals
         * or out of a filename and silently answer about the wrong day,
         * which is worse than answering about today and saying so.
         */
        val WHEN_PATTERN = Regex(
            """(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?\s*(Z|[+-]\d{2}:?\d{2})?""",
            RegexOption.IGNORE_CASE
        )

        /**
         * How far behind the weather archive runs.
         *
         * Asking it for a day inside this window answers with nulls,
         * which were printed as findings: "sunrise null, sunset
         * null". Six days, because five is the figure and a margin
         * costs one request against a forecast endpoint that does
         * have the day.
         */
        const val ARCHIVE_LAG_MS = 6L * 24 * 3600 * 1000

        /** The circle photosNear asks Panoramax about, in metres. */
        const val PANORAMAX_RADIUS_M = 400.0

        /** This app's page size there, which is not a count of what exists. */
        const val PANORAMAX_LIMIT = 30
    }

}
