package com.osint.unified.domain.executor

import com.osint.unified.data.Freshness
import com.osint.unified.network.Http
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
    private val ua = "OSINT-Unified/1.0 (research)"
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
        val events = runCatching {
            json.parseToJsonElement(get("https://api.github.com/users/$u/events/public?per_page=100")).jsonArray
        }.getOrNull()
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
            }
        }
        if (emails.isEmpty() && repos.isEmpty()) sb.append("No public activity in the last 90 days.\n")
        sb.append("\nhttps://github.com/${seg(username)}")
        sb.toString()
    }

    /** Wikidata: structured facts and linked social handles for a person,
     *  company or place. Resolves entity-valued claims to readable labels. */
    fun wikidata(query: String): Result<String> = runCatching {
        val q = URLEncoder.encode(query, "UTF-8")
        val hits = json.parseToJsonElement(
            get("https://www.wikidata.org/w/api.php?action=wbsearchentities&search=$q&language=en&format=json&limit=3")
        ).jsonObject["search"] as? JsonArray
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

        // Identifier properties carry straight to a profile URL.
        val handles = listOf(
            "P2002" to ("X/Twitter" to "https://twitter.com/"),
            "P2037" to ("GitHub" to "https://github.com/"),
            "P2003" to ("Instagram" to "https://instagram.com/"),
            "P2013" to ("Facebook" to "https://facebook.com/"),
            "P3185" to ("VK" to "https://vk.com/"),
            "P4033" to ("Mastodon" to "https://"),
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
                socialLines.add("• ${pair.first}: ${if (pair.second.isEmpty()) v else pair.second + v}")
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
        val html = get("https://t.me/s/$name", browserUa)
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
                val contribs = (json.parseToJsonElement(
                    get("https://en.wikipedia.org/w/api.php?action=query&list=usercontribs&ucuser=$e&format=json&uclimit=20")
                ).jsonObject["query"] as? JsonObject)?.get("usercontribs") as? JsonArray
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
        val results = checks.map { (name, probe) ->
            async { name to runCatching { probe() } }
        }.map { it.await() }

        val answered = results.filter { it.second.isSuccess }
        val unreachable = results.filter { it.second.isFailure }.map { it.first }
        val hits = answered.filter { !it.second.getOrNull().isNullOrBlank() }
        val misses = answered
            .filter { it.second.getOrNull().isNullOrBlank() }
            .map { it.first }

        buildString {
            // The verdict goes on the first line, because that is the
            // line the classifier reads. "Platform sweep — 'u'"
            // followed by an explanation underneath would have been
            // filed as a finding.
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
        var total: String? = null
        val centres = ArrayList<Pair<Double, Double>>()
        sets.take(25).forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val tags = o["tags"] as? JsonObject
            total = total ?: tags.str("changesets_count")
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
        total?.let { sb.append("\n\nLifetime changesets: $it") }
        if (centres.isNotEmpty()) {
            val aLat = centres.sumOf { it.first } / centres.size
            val aLon = centres.sumOf { it.second } / centres.size
            sb.append("\nCentre of recent activity: %.4f, %.4f".format(Locale.US, aLat, aLon))
            sb.append("\n(People overwhelmingly map where they live and travel.)")
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
        runCatching {
            // Same quota notice as the host-intel path. Here it would
            // be filed as a subdomain — except that the belongs test
            // above throws it out, which is luck rather than design.
            val body = get("https://api.hackertarget.com/hostsearch/?q=$d")
            if (hackertargetAnswered(body)) {
                body.lineSequence().forEach {
                    val host = it.substringBefore(",").trim()
                    if (host.isNotEmpty()) record(host, "hackertarget")
                }
            }
        }
        runCatching {
            // RapidDNS publishes an HTML table; each row's first cell is a host.
            val html = get("https://rapiddns.io/subdomain/$d?full=1", browserUa)
            Regex("""<td[^>]*>([A-Za-z0-9_.*-]+\.${Regex.escape(d)})</td>""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { record(it.groupValues[1], "rapiddns") }
        }
        runCatching {
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
        runCatching {
            // Wayback's CDX index knows hosts that no longer resolve.
            get("https://web.archive.org/cdx/search/cdx?url=*.$d&output=text&fl=original&collapse=urlkey&limit=4000")
                .lineSequence().forEach { line ->
                    val host = line.substringAfter("://", "").substringBefore("/").substringBefore(":")
                    if (host.isNotEmpty()) record(host, "wayback")
                }
        }
        if (found.isEmpty()) return@runCatching "Subdomain sweep: nothing found for $d."
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
        runCatching {
            val r = get("https://api.hackertarget.com/reverseiplookup/?q=$t").trim()
            if (hackertargetAnswered(r)) {
                val hosts = r.lines().filter { it.isNotBlank() }
                sb.append("\nOther sites on this IP (${hosts.size}):\n")
                hosts.take(60).forEach { sb.append("• $it\n") }
            }
        }
        runCatching {
            val a = get("https://api.hackertarget.com/aslookup/?q=$t").trim()
            if (hackertargetAnswered(a)) sb.append("\nAS: $a\n")
        }
        runCatching {
            val l = get("https://api.hackertarget.com/pagelinks/?q=http://$t").trim()
            if (hackertargetAnswered(l)) {
                val links = l.lines().filter { it.isNotBlank() }
                val external = links.filterNot { it.contains(t) }.distinct()
                sb.append("\nOutbound links (${external.size} external):\n")
                external.take(40).forEach { sb.append("• $it\n") }
            }
        }
        if (sb.length <= headerOnly) "Host intel: no data returned for $t." else sb.toString()
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
                val o = json.parseToJsonElement(get("https://dns.google/resolve?name=$reversed.$zone&type=A")).jsonObject
                (o["Answer"] as? JsonArray)?.mapNotNull { (it as? JsonObject).str("data") } ?: emptyList()
            }.getOrNull()
            when {
                answers == null -> unreachable.add(label)
                answers.isEmpty() -> clean.add(label)
                answers.any { it.startsWith("127.255.255.") } -> refused.add(label)
                else -> listed.add("$label → ${answers.joinToString(", ")}")
            }
        }
        if (clean.isEmpty() && listed.isEmpty() && refused.isEmpty()) {
            return@runCatching "IP reputation could not be reached for $ip: " +
                "no blocklist answered, so this says nothing either way."
        }
        buildString {
            append("IP reputation — $ip\n")
            if (listed.isEmpty()) {
                append("\nNot listed on any blocklist that answered.\n")
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

        runCatching {
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
            runCatching {
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
            runCatching {
                val prefixes = (json.parseToJsonElement(
                    get("https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS$a")
                ).jsonObject["data"]?.jsonObject?.get("prefixes") as? JsonArray)
                prefixes?.let { sb.append("Announced prefixes: ${it.size}\n") }
            }
            runCatching {
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
        if (sb.length <= headerOnly) "No network data for $t." else sb.toString()
    }

    /** AlienVault OTX, keyless: passive DNS, hosted URLs and malware seen on
     *  a domain or IP. */
    fun threatExchange(input: String): Result<String> = runCatching {
        val t = input.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val kind = if (Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(t)) "IPv4" else "domain"
        val base = "https://otx.alienvault.com/api/v1/indicators/$kind/$t"
        val sb = StringBuilder("Threat exchange (OTX) — $t\n")
        val headerOnly = sb.length

        runCatching {
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

        runCatching {
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
            }
        }

        runCatching {
            val rows = json.parseToJsonElement(get("$base/url_list?limit=20"))
                .jsonObject["url_list"] as? JsonArray
            rows?.takeIf { it.isNotEmpty() }?.let { arr ->
                sb.append("\nURLs seen (${arr.size}):\n")
                arr.take(20).forEach { r ->
                    sb.append("• ${(r as? JsonObject).str("url")?.take(140)}\n")
                }
            }
        }
        if (sb.length <= headerOnly) "OTX has nothing on $t." else sb.toString()
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
        val ciks = Regex("""CIK=(\d{10})""").findAll(xml).map { it.groupValues[1] }.distinct().toList()
        val names = Regex("""<conformed-name>([^<]+)</conformed-name>""").findAll(xml)
            .map { it.groupValues[1] }.toList()
        Regex("""<city>([^<]+)</city>.*?<state>([^<]+)</state>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.let { sb.append("Business address: ${it.groupValues[1]}, ${it.groupValues[2]}\n") }
        Regex("""<phone>([^<]+)</phone>""").find(xml)?.let { sb.append("Phone: ${it.groupValues[1]}\n") }
        Regex("""<state-of-incorporation>([^<]+)<""").find(xml)?.let { sb.append("Incorporated in: ${it.groupValues[1]}\n") }
        Regex("""<assigned-sic-desc>([^<]+)<""").find(xml)?.let { sb.append("Industry: ${it.groupValues[1]}\n") }
        if (names.isNotEmpty()) {
            sb.append("\nCompanies:\n")
            names.take(20).forEachIndexed { i, n ->
                sb.append("• $n")
                ciks.getOrNull(i)?.let { sb.append(" (CIK $it)\n  https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$it&type=&dateb=&owner=include&count=40\n") }
                    ?: sb.append("\n")
            }
        }
        val filings = Regex("""<filing-type>([^<]+)</filing-type>.*?<filing-date>([^<]+)</filing-date>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).take(15).toList()
        if (filings.isNotEmpty()) {
            sb.append("\nRecent filings:\n")
            filings.forEach { sb.append("• ${it.groupValues[1]} — ${it.groupValues[2]}\n") }
        }
        if (ciks.isEmpty() && names.isEmpty()) sb.append("No registrant matched.\n")
        // efts.sec.gov is the JSON backend and refuses a browser; the page
        // a person can actually open is EDGAR's own full-text search.
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
        val body = runCatching { get("https://keys.openpgp.org/vks/v1/by-email/$e") }.getOrNull()
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
        val index = runCatching {
            (json.parseToJsonElement(get("https://index.commoncrawl.org/collinfo.json")) as? JsonArray)
                ?.firstOrNull()?.jsonObject?.str("id")
        }.getOrNull() ?: "CC-MAIN-2026-43"
        val body = get("https://index.commoncrawl.org/$index-index?url=*.$d&output=json&limit=300")
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
            append("\nSample URLs:\n")
            urls.take(60).forEach { append("$it\n") }
        }
    }

    /** Photographs taken near a coordinate — Wikimedia Commons plus
     *  street-level imagery from KartaView and Panoramax. */
    fun photosNear(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        val sb = StringBuilder("Imagery near %.5f, %.5f\n".format(Locale.US, lat, lon))
        val headerOnly = sb.length

        runCatching {
            val pages = (json.parseToJsonElement(
                get("https://commons.wikimedia.org/w/api.php?action=query&generator=geosearch" +
                    "&ggscoord=$lat%7C$lon&ggsradius=3000&ggslimit=40&prop=imageinfo" +
                    "&iiprop=url%7Cextmetadata&format=json")
            ).jsonObject["query"] as? JsonObject)?.get("pages") as? JsonObject
            if (pages != null && pages.isNotEmpty()) {
                sb.append("\nWikimedia Commons — ${pages.size} geotagged items within 3 km:\n")
                pages.values.take(40).forEach { p ->
                    val o = p as? JsonObject ?: return@forEach
                    sb.append("• ${o.str("title")}")
                    val info = (o["imageinfo"] as? JsonArray)?.firstOrNull()?.jsonObject
                    info.str("url")?.let { sb.append("\n  $it") }
                    sb.append("\n")
                }
            }
        }

        runCatching {
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
            }
        }

        // Panoramax: open street-level imagery, keyless, worldwide, and
        // it hands back the JPEG itself rather than a viewer. Added
        // because the only other street-level option this app could
        // name was a link to a WebGL application that needs a key to
        // query and most of a minute to paint on a phone — a source
        // you cannot read is not a source.
        runCatching {
            // A degree of latitude is 111 km everywhere; a degree of
            // longitude is that times the cosine of the latitude, so a
            // square box in metres is not a square box in degrees.
            val dLat = 0.0036
            val dLon = dLat / kotlin.math.max(
                kotlin.math.cos(Math.toRadians(lat)), 0.02
            )
            val bbox = "%.6f,%.6f,%.6f,%.6f".format(
                Locale.US, lon - dLon, lat - dLat, lon + dLon, lat + dLat
            )
            val feats = json.parseToJsonElement(
                get("https://api.panoramax.xyz/api/search?bbox=$bbox&limit=30")
            ).jsonObject["features"] as? JsonArray
            val rows = feats?.mapNotNull { it as? JsonObject }.orEmpty()
            if (rows.isNotEmpty()) {
                sb.append("\nPanoramax street-level — ${rows.size} photos within ")
                sb.append("400 m, newest first:\n")
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
            }
        }

        if (sb.length <= headerOnly) {
            sb.append("\nNo geotagged imagery found here. Wider options:\n")
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
            } else {
                sb.append("\nNot currently transmitting ADS-B.\n")
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
        if (!txs.isNullOrEmpty()) {
            sb.append("\nRecent transactions:\n")
            txs.take(10).forEach { t ->
                val tx = t as? JsonObject ?: return@forEach
                val time = tx.dbl("time")?.let { utc(it.toLong() * 1000) } ?: "?"
                sb.append("• $time  ${tx.str("hash")?.take(20)}…\n")
                (tx["out"] as? JsonArray)?.forEach { out ->
                    (out as? JsonObject)?.str("addr")?.takeIf { it != a }?.let { counterparties.add(it) }
                }
            }
        }
        if (counterparties.isNotEmpty()) {
            sb.append("\nCounterparty addresses (${counterparties.size}):\n")
            counterparties.take(20).forEach { sb.append("• $it\n") }
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
            sb.append("No public node answered, so nothing is known about this address.\n")
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
        val domain = Regex("""[@\s]([a-z0-9-]+(?:\.[a-z0-9-]+)+)\s*$""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.lowercase()
            ?: return@runCatching "Give a name and a domain, e.g. 'Jane Doe @example.com'."
        val nameParts = raw.substringBeforeLast(domain).trim().trimEnd('@').trim()
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
                            get("https://dns.google/resolve?name=$cand&type=A")
                        ).jsonObject
                        (o["Answer"] as? JsonArray)?.mapNotNull { (it as? JsonObject).str("data") }
                            ?.filter { Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(it) } ?: emptyList()
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

        val now = Date()
        val sun = solarPosition(lat, lon, now)
        sb.append("\nSun right now (${utc(now.time)}):\n")
        sb.append("Elevation: %.1f°\n".format(Locale.US, sun.first))
        sb.append("Azimuth: %.1f° (compass bearing to the sun)\n".format(Locale.US, sun.second))
        if (sun.first > 0) {
            val shadowBearing = (sun.second + 180.0) % 360.0
            val ratio = 1.0 / tan(Math.toRadians(sun.first))
            sb.append("Shadows fall toward %.0f° and are %.2f× the object's height.\n".format(Locale.US, shadowBearing, ratio))
        } else {
            sb.append("The sun is below the horizon here — it is night.\n")
        }

        // Sunrise/sunset and today's weather, for verifying a claimed timestamp.
        runCatching {
            val o = json.parseToJsonElement(
                get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min,precipitation_sum&timezone=UTC&forecast_days=1")
            ).jsonObject["daily"]?.jsonObject
            fun first(k: String) = ((o?.get(k) as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            sb.append("\nToday (UTC): sunrise ${first("sunrise")}, sunset ${first("sunset")}\n")
            sb.append("Temp ${first("temperature_2m_min")}–${first("temperature_2m_max")}°C, ")
            sb.append("precipitation ${first("precipitation_sum")} mm\n")
        }
        sb.append("\nSun path tool: https://www.suncalc.org/#/$lat,$lon,15/")
        sb.toString()
    }

    /** Historical weather at a coordinate — tests whether footage or a photo
     *  matches the conditions on the date it claims. */
    fun historicalWeather(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, -31)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 24)
        val end = fmt.format(cal.time)
        val o = json.parseToJsonElement(
            get("https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon" +
                "&start_date=$start&end_date=$end&daily=temperature_2m_max,temperature_2m_min," +
                "precipitation_sum,snowfall_sum,weathercode&timezone=UTC")
        ).jsonObject["daily"]?.jsonObject ?: return@runCatching "No archive data for that point."
        fun col(k: String) = (o[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "-" } ?: emptyList()
        val days = col("time"); val tmax = col("temperature_2m_max"); val tmin = col("temperature_2m_min")
        val rain = col("precipitation_sum"); val snow = col("snowfall_sum"); val code = col("weathercode")
        val sb = StringBuilder("Historical weather — %.4f, %.4f\n$start to $end (UTC)\n\n".format(Locale.US, lat, lon))
        days.indices.forEach { i ->
            sb.append("${days[i]}  ${tmin.getOrNull(i)}–${tmax.getOrNull(i)}°C  ")
            sb.append("rain ${rain.getOrNull(i)}mm  snow ${snow.getOrNull(i)}cm  ")
            sb.append(wmo(code.getOrNull(i)?.toDoubleOrNull()?.toInt()))
            sb.append("\n")
        }
        sb.append("\nUse this to check whether a photo's weather matches its claimed date.")
        sb.toString()
    }

    /** UK street-level crime within a mile of a coordinate (data.police.uk). */
    fun ukCrime(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        val arr = json.parseToJsonElement(
            get("https://data.police.uk/api/crimes-street/all-crime?lat=$lat&lng=$lon")
        ) as? JsonArray ?: return@runCatching "No UK crime data for that point (UK only)."
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
            .jsonObject["results"] as? JsonObject) ?: return@runCatching "No CVEs found for $vendor $product."
        val sb = StringBuilder("CVEs for $vendor $product\n")
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
        if (sb.length <= headerOnly) "No CVEs found for $vendor $product." else sb.toString()
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

    private fun parseCoord(input: String): Pair<Double, Double>? {
        val p = input.trim().split(",")
        if (p.size < 2) return null
        val lat = p[0].trim().toDoubleOrNull() ?: return null
        val lon = p[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
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
                .header("User-Agent", agent)
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return body
                last = IOException("HTTP ${resp.code}")
                // A 4xx is an answer; only a 5xx is worth asking again.
                if (resp.code < 500) throw last!!
            }
        }
        throw last ?: IOException("request failed")
    }
}
