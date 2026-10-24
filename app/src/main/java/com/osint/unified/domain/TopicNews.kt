package com.osint.unified.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Following a subject rather than a target: the subscribe half of the
 * briefing.
 *
 * A watch on a target re-runs passive sources and reports which
 * identifiers changed. A topic has no identifiers — it has coverage —
 * so a topic watch collects what two keyless news indexes carry on it,
 * remembers which headlines it has already reported, and on each check
 * reports only the ones it has not, grouped into stories by
 * [StoryClusters] so thirty outlets rewriting one wire report read as
 * one story carried by thirty, not as thirty pieces of news.
 *
 * Two sources because either alone has a blind side. GDELT indexes the
 * world's press in a hundred languages and is rate-limited per address;
 * Google News's RSS is English-leaning, quick, and answers when GDELT
 * is refusing. Neither needs a key or an account.
 *
 * Everything here is pure, so it is tested; the fetching is in the
 * watch repository.
 */
object TopicNews {

    /** How many headlines a topic remembers having reported. */
    const val MEMORY = 600

    /**
     * Older than this and a headline is not reported as new, even if it
     * has not been seen before. The memory is finite and a feed can
     * resurface a week-old item; "new" means new to the world as well as
     * to the watch.
     */
    const val NEW_WITHIN_MS = 48L * 3600 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * GDELT's article list.
     *
     * Throws on anything that is not GDELT's JSON — its rate-limit
     * sentence, an error page — because that is a source that did not
     * answer, not one that found no coverage. GDELT's own "nothing"
     * is `{}`, which parses and has no articles.
     */
    fun parseGdelt(body: String): List<StoryClusters.Article> {
        val text = body.trim()
        if (!text.startsWith("{")) {
            throw IOException("GDELT answered with text, not results: " + text.take(80))
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw IOException("GDELT's answer could not be read", it)
        }
        // empty-is-an-answer: `{}` is how GDELT says it has no coverage;
        // it parsed, so it is an answer.
        val articles = root["articles"] as? JsonArray ?: return emptyList()
        return articles.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val title = s("title") ?: return@mapNotNull null
            StoryClusters.Article(
                title = title,
                domain = s("domain"),
                url = s("url"),
                seen = s("seendate"),
                country = s("sourcecountry")
            )
        }
    }

    /**
     * Google News search RSS.
     *
     * Its titles carry the publisher on the end — "Headline - Reuters" —
     * which is taken off, so the same story from two outlets has the same
     * headline to compare. Throws on a body that is not RSS.
     */
    fun parseGoogleRss(body: String): List<StoryClusters.Article> {
        if (!body.contains("<rss") && !body.contains("<channel")) {
            throw IOException("Google News answered with something that is not a feed")
        }
        return ITEM.findAll(body).mapNotNull { m ->
            val item = m.groupValues[1]
            val source = SOURCE.find(item)
            val publisher = source?.groupValues?.get(2)?.let(::unescape)?.trim()
            val publisherHost = source?.groupValues?.get(1)?.let(::hostOf)
            var title = TITLE.find(item)?.groupValues?.get(1)?.let(::unescape)?.trim()
                ?: return@mapNotNull null
            if (publisher != null && title.endsWith(" - $publisher")) {
                title = title.dropLast(publisher.length + 3).trim()
            }
            if (title.isEmpty()) return@mapNotNull null
            StoryClusters.Article(
                title = title,
                domain = publisherHost ?: publisher,
                url = LINK.find(item)?.groupValues?.get(1)?.trim(),
                seen = PUB_DATE.find(item)?.groupValues?.get(1)?.let(::stampOfRfc822),
                country = null
            )
        }.toList()
    }

    /**
     * What makes two headlines the same headline: the words, in order,
     * without case or punctuation. Hashed, because six hundred of them
     * are kept in a row of the database.
     */
    fun key(title: String): String {
        val words = title.lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()
            .replace(SPACES, " ")
            .take(160)
        val digest = MessageDigest.getInstance("SHA-1").digest(words.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * The articles in [fresh] not in [seen], new enough to report, one
     * per headline.
     */
    fun unseen(
        fresh: List<StoryClusters.Article>,
        seen: Set<String>,
        now: Long
    ): Map<String, StoryClusters.Article> {
        val since = stamp(now - NEW_WITHIN_MS)
        val out = LinkedHashMap<String, StoryClusters.Article>()
        fresh.forEach { a ->
            val k = key(a.title)
            if (k in seen || k in out) return@forEach
            // A date that cannot be read is given the benefit of the
            // doubt; one that can and is old is not news.
            if (a.seen != null && a.seen.length >= 8 && a.seen < since) return@forEach
            out[k] = a
        }
        return out
    }

    /**
     * Every headline key in [fresh], for the memory — whether or not it
     * was new enough to report. An old item not reported this time must
     * not be reported next time either.
     */
    fun keysOf(fresh: List<StoryClusters.Article>): List<String> =
        fresh.map { key(it.title) }.distinct()

    /** What a watch remembers after this check: the newest first, bounded. */
    fun remember(previous: List<String>, fresh: List<String>): List<String> =
        (fresh + previous).distinct().take(MEMORY)

    /**
     * One line for the watch log and the notification.
     *
     * Led by the count of stories, not articles, and each story with how
     * many outlets carry it — the thing a flat list of headlines hides.
     */
    fun describe(topic: String, fresh: List<StoryClusters.Article>): String? {
        if (fresh.isEmpty()) return null
        val stories = StoryClusters.cluster(fresh)
        val head = buildString {
            append("${fresh.size} new ")
            append(if (fresh.size == 1) "article" else "articles")
            append(" on “$topic”")
            if (stories.size > 1) append(" in ${stories.size} stories")
        }
        val lines = stories.take(SHOWN).joinToString("; ") { c ->
            val outlets = c.publishers
            "${c.headline.take(HEADLINE_CHARS)}" +
                if (outlets > 1) " ($outlets outlets)" else ""
        }
        val more = if (stories.size > SHOWN) "; and ${stories.size - SHOWN} more" else ""
        return "$head: $lines$more"
    }

    /** How a topic's first check reads, before there is anything to compare. */
    fun baseline(topic: String, fresh: List<StoryClusters.Article>): String {
        val stories = StoryClusters.cluster(fresh).size
        return "Watching “$topic” from now: ${fresh.size} " +
            (if (fresh.size == 1) "article" else "articles") +
            " in the last day, in $stories " + (if (stories == 1) "story" else "stories") +
            ". New coverage will be reported from the next check."
    }

    /** yyyyMMddTHHmmssZ, GDELT's form, so dates from both sources compare as strings. */
    fun stamp(millis: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(millis))

    private fun stampOfRfc822(text: String): String? = runCatching {
        val parsed = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(text.trim())
        parsed?.let { stamp(it.time) }
    }.getOrNull()

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url.trim()).host?.removePrefix("www.") }.getOrNull()

    private fun unescape(s: String): String = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&amp;", "&")

    private const val SHOWN = 3
    private const val HEADLINE_CHARS = 110

    private val ITEM = Regex("""(?s)<item>(.{1,20000}?)</item>""")
    private val TITLE = Regex("""(?s)<title>(.{1,600}?)</title>""")
    private val LINK = Regex("""(?s)<link>(.{1,2000}?)</link>""")
    private val PUB_DATE = Regex("""<pubDate>([^<]{1,64})</pubDate>""")
    private val SOURCE = Regex("""<source url="([^"]{1,300})">([^<]{1,200})</source>""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex(""" {2,}""")
}
