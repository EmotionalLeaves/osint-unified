package com.osint.unified.domain

/**
 * Grouping news articles that are about the same thing.
 *
 * A list of seventy-five headlines is not a briefing. Most of them
 * are the same three stories rewritten, and the thing worth knowing
 * — that one of those stories is carried by thirty outlets and
 * another by one — is invisible in a flat list ordered by time.
 *
 * So: cluster on the words the headlines share, order by how many
 * independent publishers carry each story rather than by how many
 * articles there are, and date each cluster from first seen to
 * last. That is most of what a collection-and-triage tool does, and
 * none of it needs a server, a key or a model.
 *
 * Deliberately simple. Headline overlap is a crude similarity and
 * it will occasionally split one story in two; that is a better
 * failure than merging two stories into one, because a reader can
 * see two entries about the same event and a reader cannot see an
 * event that was absorbed into another.
 */
object StoryClusters {

    data class Article(
        val title: String,
        val domain: String?,
        val url: String?,
        /** GDELT's seendate, yyyyMMdd… — compared as a string. */
        val seen: String?,
        val country: String?
    )

    data class Cluster(
        val headline: String,
        val articles: List<Article>
    ) {
        /** Publishers, not articles. One outlet running a story four
         *  times is one outlet. */
        val publishers: Int get() = articles.mapNotNull { it.domain }.distinct().size
        val countries: List<String>
            get() = articles.mapNotNull { it.country }.filter { it.isNotBlank() }.distinct()
        val first: String? get() = articles.mapNotNull { it.seen }.minOrNull()
        val last: String? get() = articles.mapNotNull { it.seen }.maxOrNull()
    }

    /**
     * Words that are in every headline and therefore distinguish
     * none of them. Short, because a long stop list starts throwing
     * away the words that matter in a specific story.
     */
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at",
        "for", "with", "by", "from", "as", "is", "are", "was", "were", "be",
        "been", "has", "have", "had", "will", "would", "can", "could", "may",
        "says", "said", "after", "over", "into", "that", "this", "it", "its",
        "new", "news", "live", "updates", "update", "report", "reports",
        "video", "watch", "read", "more", "amp"
    )

    private val WORD = Regex("""[\p{L}\p{N}][\p{L}\p{N}'-]*""")

    /** The words in a headline worth comparing. */
    internal fun terms(title: String): Set<String> =
        WORD.findAll(title.lowercase())
            .map { it.value.trim('\'', '-') }
            .filter { it.length > 2 && it !in STOP }
            .toSet()

    /**
     * How alike two headlines are, as the share of the smaller one's
     * words that the larger also has.
     *
     * Not Jaccard: a four-word headline and a fourteen-word one
     * about the same event share at most four words, which Jaccard
     * scores at 0.29 and calls unrelated. Containment handles the
     * wire-copy-plus-commentary shape these feeds are full of.
     */
    internal fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / minOf(a.size, b.size)
    }

    /**
     * Clusters, most widely carried first.
     *
     * Single-link agglomeration against the cluster's seed rather
     * than against every member: chaining — A near B, B near C, C
     * nothing like A — is how naive clustering ends up with one
     * group containing everything.
     */
    fun cluster(articles: List<Article>, threshold: Double = 0.5): List<Cluster> {
        val seeds = ArrayList<Pair<Set<String>, MutableList<Article>>>()
        articles.forEach { article ->
            val t = terms(article.title)
            if (t.isEmpty()) {
                seeds.add(emptySet<String>() to mutableListOf(article))
                return@forEach
            }
            val home = seeds.firstOrNull { (seedTerms, _) ->
                overlap(t, seedTerms) >= threshold
            }
            if (home != null) home.second.add(article)
            else seeds.add(t to mutableListOf(article))
        }
        return seeds
            .map { (_, members) ->
                // The headline shown is the shortest one, which is
                // almost always the plainest: wire copy before a
                // site has added its own framing to it.
                Cluster(
                    members.minByOrNull { it.title.length }?.title ?: members.first().title,
                    members
                )
            }
            .sortedWith(
                compareByDescending<Cluster> { it.publishers }
                    .thenByDescending { it.articles.size }
                    .thenByDescending { it.last ?: "" }
            )
    }

    /** yyyyMMddThhmmssZ, or whatever prefix of it is there. */
    fun day(stamp: String?): String? {
        if (stamp == null || stamp.length < 8) return null
        return "${stamp.substring(0, 4)}-${stamp.substring(4, 6)}-${stamp.substring(6, 8)}"
    }
}
