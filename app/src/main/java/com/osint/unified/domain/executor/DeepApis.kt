package com.osint.unified.domain.executor

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
import okhttp3.OkHttpClient
import okhttp3.Request
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
class DeepApis(private val client: OkHttpClient = Http.client) {

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
        sb.append("\nProfile: https://keybase.io/$username")
        sb.toString()
    }

    /** Reddit public profile plus recent comment/post subreddits. Reddit blocks
     *  datacenter ranges, so this is best-effort from a phone. */
    fun reddit(username: String): Result<String> = runCatching {
        val u = URLEncoder.encode(username, "UTF-8")
        val about = runCatching {
            json.parseToJsonElement(get("https://www.reddit.com/user/$u/about.json", browserUa))
                .jsonObject["data"]?.jsonObject
        }.getOrNull() ?: return@runCatching "Reddit: no public data for u/$username (or Reddit blocked the request).\nhttps://www.reddit.com/user/$username"
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
        sb.append("\nhttps://www.reddit.com/user/$username")
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
        sb.append("https://news.ycombinator.com/user?id=$username")
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
        sb.append("\nhttps://github.com/$username")
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
        if (!html.contains("tgme_channel_info") && !html.contains("tgme_page")) {
            return@runCatching "Telegram: no public channel '@$name' (or it is private)."
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
        sb.append("\nhttps://t.me/$name")
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
            if (h.endsWith(d) && h.isNotEmpty()) found.getOrPut(h) { LinkedHashSet() }.add(src)
        }
        runCatching {
            get("https://api.hackertarget.com/hostsearch/?q=$d").lineSequence().forEach {
                val host = it.substringBefore(",").trim()
                if (host.isNotEmpty()) record(host, "hackertarget")
            }
        }
        runCatching {
            // RapidDNS publishes an HTML table; each row's first cell is a host.
            val html = get("https://rapiddns.io/subdomain/$d?full=1", browserUa)
            Regex("""<td[^>]*>([A-Za-z0-9_.*-]+\.${Regex.escape(d)})</td>""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { record(it.groupValues[1], "rapiddns") }
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
        val sb = StringBuilder("Subdomain sweep — $d\n${found.size} unique hosts from hackertarget + rapiddns + wayback:\n\n")
        found.keys.sorted().forEach { h -> sb.append("$h  [${found[h]?.joinToString("/")}]\n") }
        sb.toString()
    }

    /** hackertarget's keyless toolbox: reverse IP, AS lookup, page links. */
    fun hostIntel(input: String): Result<String> = runCatching {
        val t = input.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val sb = StringBuilder("Host intel — $t\n")
        runCatching {
            val r = get("https://api.hackertarget.com/reverseiplookup/?q=$t").trim()
            if (r.isNotBlank() && !r.startsWith("error")) {
                val hosts = r.lines().filter { it.isNotBlank() }
                sb.append("\nOther sites on this IP (${hosts.size}):\n")
                hosts.take(60).forEach { sb.append("• $it\n") }
            }
        }
        runCatching {
            val a = get("https://api.hackertarget.com/aslookup/?q=$t").trim()
            if (a.isNotBlank() && !a.startsWith("error")) sb.append("\nAS: $a\n")
        }
        runCatching {
            val l = get("https://api.hackertarget.com/pagelinks/?q=http://$t").trim()
            if (l.isNotBlank() && !l.startsWith("error")) {
                val links = l.lines().filter { it.isNotBlank() }
                val external = links.filterNot { it.contains(t) }.distinct()
                sb.append("\nOutbound links (${external.size} external):\n")
                external.take(40).forEach { sb.append("• $it\n") }
            }
        }
        if (sb.length < 30) "Host intel: no data returned for $t." else sb.toString()
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
        // SEC requires a descriptive UA with contact details.
        val xml = get(
            "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&company=$q&type=&dateb=&owner=include&count=20&output=atom",
            "OSINT Unified research tool (contact via github.com/EmotionalLeaves/osint-unified)"
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
        sb.append("\nFull-text search: https://efts.sec.gov/LATEST/search-index?q=$q")
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
            }.onFailure { sb.append("hexdb: no record for Mode-S $hex\n") }
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
                    sb.append("Position: %.4f, %.4f\n".format(la, lo))
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
            sb.append("\nHistory: https://globe.adsb.lol/?reg=$reg\n")
            sb.append("Photos: https://www.jetphotos.com/registration/$reg\n")
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
        o.dbl("final_balance")?.let { sb.append("Balance: %.8f BTC\n".format(it / sat)) }
        o.dbl("total_received")?.let { sb.append("Total received: %.8f BTC\n".format(it / sat)) }
        o.dbl("total_sent")?.let { sb.append("Total sent: %.8f BTC\n".format(it / sat)) }
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

    private fun ethWallet(a: String): String {
        val sb = StringBuilder("Ethereum address — $a\n")
        runCatching {
            val o = json.parseToJsonElement(
                get("https://api.ethplorer.io/getAddressInfo/$a?apiKey=freekey")
            ).jsonObject
            ((o["ETH"] as? JsonObject)?.dbl("balance"))?.let { sb.append("Balance: %.6f ETH\n".format(it)) }
            o.int("countTxs")?.let { sb.append("Transactions: $it\n") }
            val tokens = o["tokens"] as? JsonArray
            if (!tokens.isNullOrEmpty()) {
                sb.append("\nTokens held (${tokens.size}):\n")
                tokens.take(20).forEach { t ->
                    val ti = (t as? JsonObject)?.get("tokenInfo") as? JsonObject ?: return@forEach
                    sb.append("• ${ti.str("name") ?: "?"} (${ti.str("symbol") ?: "?"})\n")
                }
            }
            (o["contractInfo"] as? JsonObject)?.let { sb.append("\nThis address is a contract.\n") }
        }.onFailure { sb.append("Balance lookup unavailable right now.\n") }
        sb.append("\nExplorer: https://etherscan.io/address/$a")
        sb.append("\nLabels: https://etherscan.io/address/$a#analytics")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Geospatial analysis
    // ------------------------------------------------------------------

    /** Chronolocation helper: terrain elevation, solar position now, shadow
     *  direction and length ratio, sunrise/sunset. Used to test whether a
     *  photo's shadows match a claimed place and time. */
    fun sunAndTerrain(input: String): Result<String> = runCatching {
        val (lat, lon) = parseCoord(input) ?: return@runCatching "Not a coordinate: $input"
        val sb = StringBuilder("Terrain & sun — %.5f, %.5f\n".format(lat, lon))
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
        sb.append("Elevation: %.1f°\n".format(sun.first))
        sb.append("Azimuth: %.1f° (compass bearing to the sun)\n".format(sun.second))
        if (sun.first > 0) {
            val shadowBearing = (sun.second + 180.0) % 360.0
            val ratio = 1.0 / tan(Math.toRadians(sun.first))
            sb.append("Shadows fall toward %.0f° and are %.2f× the object's height.\n".format(shadowBearing, ratio))
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
        val sb = StringBuilder("Historical weather — %.4f, %.4f\n$start to $end (UTC)\n\n".format(lat, lon))
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
        if (arr.isEmpty()) return@runCatching "UK crime data: nothing reported near %.4f, %.4f (or outside the UK).".format(lat, lon)
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
        val sb = StringBuilder("UK street crime — %.4f, %.4f\n".format(lat, lon))
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
                    sb.append("\nEPSS: %.2f%% chance of exploitation in the next 30 days".format(pct))
                }
                d?.str("percentile")?.let {
                    sb.append(" — more likely than %.1f%% of all CVEs".format((it.toDoubleOrNull() ?: 0.0) * 100))
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
        if (sb.length < 40) "No CVEs found for $vendor $product." else sb.toString()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

        val gmst = (6.697374558 + 0.06570982441908 * Math.floor(n) + 1.00273790935 * hour).mod(24.0)
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
        val request = Request.Builder().url(url)
            .header("User-Agent", agent)
            .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }
}
