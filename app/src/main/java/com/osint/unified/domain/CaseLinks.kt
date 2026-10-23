package com.osint.unified.domain

/**
 * What ties the targets of a case together.
 *
 * A case with three people in it is three dossiers until something is
 * found in two of them. That moment — the shared mailbox, the shared
 * host, the name of one target turning up in another's results — is the
 * only thing that makes it a case rather than a folder, and it is the
 * one piece of analysis nobody can do by reading the dossiers one at a
 * time, because the identifier that matters is unremarkable in each.
 *
 * So the app does it. Every target's findings are compared, and anything
 * asserted under more than one is reported as a link, with the targets
 * it joins and the sources behind it.
 */
object CaseLinks {

    /**
     * One identifier that appears under more than one target.
     *
     * [targets] are the seeds whose dossiers assert it; [independent] is
     * how many separate source families did, across all of them.
     */
    data class Link(
        val kind: String,
        val value: String,
        val targets: List<String>,
        val sources: List<String>,
        val independent: Int,
        val reliability: Grading.Reliability,
        val credibility: Grading.Credibility,
        /** This value is itself another target of the case. */
        val isTarget: Boolean
    ) {
        val grade: String get() = Grading.code(reliability, credibility)

        val sourceNames: List<String>
            get() = sources.map { ToolRegistry.byId(it)?.name ?: it }
    }

    /**
     * Links across a case's targets.
     *
     * [byTarget] maps each target's seed to the findings of its dossier.
     *
     * Machinery and struck findings are excluded before anything is
     * compared, and this is the whole difficulty of the feature rather
     * than a detail: nearly every website on Earth sits behind the same
     * handful of content delivery networks, so without it every pair of
     * targets in every case would "share" Cloudflare, and a screen whose
     * job is to show the one real connection would bury it under twenty
     * false ones. An investigator who is shown twenty links where there
     * is one stops reading the links.
     */
    fun find(byTarget: Map<String, List<Corroboration.Entity>>): List<Link> {
        if (byTarget.size < 2) return emptyList()
        val seeds = byTarget.keys.map { it.trim().lowercase() }.toSet()

        data class Acc(
            val targets: LinkedHashSet<String> = LinkedHashSet(),
            val sources: LinkedHashSet<String> = LinkedHashSet(),
            var independent: Int = 0,
            var reliability: Grading.Reliability = Grading.Reliability.UNJUDGED
        )

        val found = LinkedHashMap<Pair<String, String>, Acc>()
        byTarget.forEach { (target, entities) ->
            entities.forEach { e ->
                // Machinery and anything the investigator struck are not
                // evidence of a connection. See above: this line is the
                // difference between a useful screen and a useless one.
                if (e.caveat != null) return@forEach
                // A target asserting its own seed is not a link to
                // itself, and would otherwise appear in every case.
                if (e.value.equals(target.trim(), ignoreCase = true)) return@forEach
                val acc = found.getOrPut(e.kind to e.value) { Acc() }
                acc.targets.add(target)
                acc.sources.addAll(e.sources)
                acc.independent = maxOf(acc.independent, e.independent)
                if (e.reliability < acc.reliability) acc.reliability = e.reliability
            }
        }

        return found
            .filter { it.value.targets.size >= 2 }
            .map { (key, acc) ->
                val (kind, value) = key
                // Two targets' dossiers agreeing is corroboration in its
                // own right, and of a better kind than two tools reading
                // the same upstream: these are separate enquiries that
                // happened to meet. Counted as agreement, but through the
                // same scale as everything else rather than a special one.
                val agreeing = acc.independent - 1 + (acc.targets.size - 1)
                Link(
                    kind = kind,
                    value = value,
                    targets = acc.targets.toList(),
                    sources = acc.sources.toList(),
                    independent = acc.independent,
                    reliability = acc.reliability,
                    credibility = Grading.credibility(acc.reliability, agreeing = agreeing),
                    isTarget = value.trim().lowercase() in seeds
                )
            }
            .sortedWith(
                compareByDescending<Link> { it.targets.size }
                    .thenByDescending { it.isTarget }
                    .thenBy { it.credibility.code }
                    .thenBy { it.reliability.code }
                    .thenBy { it.value }
            )
    }

    /** One line saying what, if anything, holds the case together. */
    fun summary(links: List<Link>, targetCount: Int): String {
        if (targetCount < 2) {
            return "One target. A case needs two before anything can connect them."
        }
        if (links.isEmpty()) {
            return "$targetCount targets · nothing found in more than one of them"
        }
        val strong = links.count { it.credibility.code <= 2 }
        val named = links.count { it.isTarget }
        return buildString {
            append("$targetCount targets · ${links.size} shared ")
            append(if (links.size == 1) "identifier" else "identifiers")
            if (strong > 0) append(" · $strong well-supported")
            if (named > 0) append(" · $named names another target outright")
        }
    }

    /**
     * The tags used across a case's targets, most used first.
     *
     * Stored as free text per target because the words that matter in an
     * enquiry are the enquiry's own; gathered here so the case can be
     * read by them.
     */
    fun tagsOf(raw: Iterable<String>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        raw.forEach { field ->
            split(field).forEach { tag -> counts[tag] = (counts[tag] ?: 0) + 1 }
        }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        ).map { it.key to it.value }
    }

    /** One tags field into clean, deduplicated, lowercased labels. */
    fun split(raw: String): List<String> =
        raw.split(',', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

    /** Labels back into the single field the row stores. */
    fun join(tags: Iterable<String>): String = split(tags.joinToString(",")).joinToString(", ")

    /** Adds or removes one label without disturbing the others. */
    fun toggle(raw: String, tag: String): String {
        val clean = tag.trim().lowercase()
        if (clean.isBlank()) return raw
        val current = split(raw)
        return join(if (clean in current) current - clean else current + clean)
    }
}
