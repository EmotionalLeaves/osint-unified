package com.osint.unified.domain

/**
 * What a profile page says about who owns it.
 *
 * A username sweep answers a question nobody asked: is this name taken
 * here. Forty sites saying yes about "jdoe" is forty facts about the
 * string "jdoe". The question an investigator has is whether any of them
 * belong to the same person, and the pages themselves usually say — the
 * same display name, the same sentence of biography, the same link out,
 * the same avatar file.
 *
 * So this reads those four things off a page, and the grouping below
 * finds the accounts that share one. That is the difference between
 * "forty possible accounts" and "six that agree with each other", and it
 * is the largest accuracy gain available without adding a single new
 * source.
 */
object ProfileSignals {

    data class Signals(
        val displayName: String? = null,
        val bio: String? = null,
        val avatar: String? = null,
        /** Addresses the profile links out to, which are the strongest tie. */
        val links: List<String> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = displayName == null && bio == null && avatar == null && links.isEmpty()
    }

    private val META = Regex(
        """<meta[^>]+(?:property|name)\s*=\s*["']([^"']+)["'][^>]+content\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val META_REVERSED = Regex(
        """<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]+(?:property|name)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(
        RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL
    ))
    private val LINK = Regex("""https?://[^\s"'<>)]+""")

    /**
     * Hosts a profile links to that say nothing about its owner.
     *
     * Every page links to its own platform, to a privacy policy, to a
     * font provider. Those are the page's furniture; a link is only a
     * signal when it points somewhere the owner chose.
     */
    private val FURNITURE = listOf(
        "schema.org", "w3.org", "gstatic", "googleapis", "google.com",
        "gravatar.com", "fonts.", "cdn.", "static.", "assets.",
        "policies.", "support.", "help.", "about.", "legal.",
        "apple.com", "play.google.com", "itunes.apple.com",
        "facebook.com/tr", "doubleclick", "googletagmanager", "licdn.com",
        "twimg.com", "fbcdn.net", "cdninstagram", "akamaized", "cloudfront",
        // Platform boilerplate that two unrelated profiles share
        // simply by being hosted on the same kind of thing. A shared
        // link is called "the strongest tie available here", so a
        // theme credit or a share button counted as two people being
        // one person.
        "wordpress.org", "wordpress.com/plugins", "wp.me",
        "discord.gg", "discord.com/invite", "t.co",
        "twitter.com/intent", "x.com/intent", "github.com/sponsors",
        "creativecommons.org", "gnu.org", "mozilla.org",
        "patreon.com/join", "buymeacoffee.com/widget",
        "ko-fi.com/widget", "paypal.com/donate", "linktr.ee/s",
        "youtube.com/embed", "player.vimeo.com", "archive.org/web"
    )

    fun read(html: String, pageHost: String): Signals {
        if (html.isBlank()) return Signals()
        val meta = HashMap<String, String>()
        META.findAll(html).forEach { meta.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2]) }
        META_REVERSED.findAll(html).forEach {
            meta.putIfAbsent(it.groupValues[2].lowercase(), it.groupValues[1])
        }

        fun pick(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { meta[it]?.trim()?.takeIf { v -> v.isNotBlank() } }

        val title = TITLE.find(html)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

        val name = pick("og:title", "twitter:title", "profile:username") ?: title
        val bio = pick("og:description", "twitter:description", "description")
        val avatar = pick("og:image", "twitter:image", "twitter:image:src")

        // Links out, minus the page's own platform and the furniture.
        val links = LINK.findAll(html.take(MAX_SCANNED))
            .map { it.value.trimEnd('.', ',', ')', '"', '\'') }
            .filter { link ->
                val host = hostOf(link)
                host.isNotBlank() &&
                    !host.endsWith(pageHost) &&
                    FURNITURE.none { link.contains(it, ignoreCase = true) }
            }
            .map { normaliseLink(it) }
            .distinct()
            .take(MAX_LINKS)
            .toList()

        return Signals(
            displayName = name?.take(120),
            bio = bio?.take(400),
            avatar = avatar?.take(400),
            links = links
        )
    }

    /** One account, with whatever its page gave up. */
    data class Account(val site: String, val url: String, val signals: Signals)

    /** Accounts that agree with each other, and what they agree on. */
    data class Cluster(val reason: String, val accounts: List<Account>)

    /**
     * Groups accounts that share a signal.
     *
     * A shared outbound link is the strongest tie available here: two
     * profiles both pointing at the same personal site is a deliberate
     * act by whoever owns them. A shared avatar address comes next, then
     * an identical biography, then an identical display name — which is
     * the weakest, because a display name is just the username again on
     * many sites.
     */
    fun cluster(accounts: List<Account>, searchedFor: String = ""): List<Cluster> {
        // The name that was searched for is not evidence about a
        // person.
        //
        // On a large share of sites the profile title *is* the bare
        // username, and displayName falls back to <title>, so forty
        // unrelated accounts that share nothing but the handle you
        // typed were grouped together and presented under "These
        // profiles also carry the same details, which is a fact about
        // a person". It is a fact about the name, which the heading
        // two lines above says explicitly and then contradicts.
        val handle = normaliseText(searchedFor)
        val clusters = ArrayList<Cluster>()
        val seen = HashSet<String>()

        fun group(reason: String, key: (Account) -> List<String>) {
            val byKey = HashMap<String, MutableList<Account>>()
            accounts.forEach { account ->
                key(account).forEach { k ->
                    if (k.isNotBlank()) byKey.getOrPut(k) { mutableListOf() }.add(account)
                }
            }
            byKey.entries
                .filter { it.value.size >= 2 }
                .sortedByDescending { it.value.size }
                .forEach { (k, group) ->
                    val fingerprint = group.map { it.url }.sorted().joinToString("|")
                    if (seen.add(fingerprint)) {
                        clusters.add(Cluster("$reason: $k", group.distinctBy { it.url }))
                    }
                }
        }

        group("same link out") { it.signals.links }
        group("same avatar") { listOfNotNull(it.signals.avatar) }
        group("same biography") {
            listOfNotNull(it.signals.bio?.let(::normaliseText)?.takeIf { b -> b.length >= 12 })
        }
        group("same display name") {
            listOfNotNull(
                it.signals.displayName
                    ?.let(::normaliseText)
                    ?.takeIf { n -> n.length >= 3 && (handle.isBlank() || n != handle) }
            )
        }
        return clusters
    }

    internal fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.lowercase()?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    /** The same link written two ways is the same link. */
    internal fun normaliseLink(url: String): String {
        val host = hostOf(url)
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
            .trimEnd('/')
        return if (host.isBlank()) url.lowercase() else "$host$path".lowercase()
    }

    private fun normaliseText(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private const val MAX_SCANNED = 200_000
    private const val MAX_LINKS = 12
}
