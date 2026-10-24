package com.osint.unified.domain

import com.osint.unified.data.db.Result as ResultRow
import kotlinx.serialization.Serializable

/**
 * Turns an investigation's results into a link graph: the seed observable in
 * the center, each tool that ran around it, and the findings each tool
 * produced as leaf nodes.
 */
object GraphBuilder {

    @Serializable data class Node(val id: String, val label: String, val group: Int)
    @Serializable data class Link(val source: String, val target: String)
    @Serializable data class Graph(val nodes: List<Node>, val links: List<Link>)

    fun build(seed: String, results: List<ResultRow>): Graph {
        val nodes = LinkedHashMap<String, Node>()
        val links = mutableListOf<Link>()

        val seedId = "seed"
        nodes[seedId] = Node(seedId, seed, 0)

        // Only what was found.
        //
        // `status == "success"` includes every tool that reached its
        // source and was told there is nothing there, and every tool
        // whose failure was reported in prose — Findings.kindOf
        // exists because those are different things. So "No accounts
        // found for this username." was drawn as a tool node with a
        // leaf hanging off it, visually identical to a real finding,
        // on the screen most likely to be screenshotted. Timeline
        // already filters this way.
        //
        // And rows still in flight belong in none of the three.
        // `Findings.kindOf` maps "running" through its `else` to
        // UNREACHABLE, so tapping GRAPH during a search drew "38
        // could not be asked" about thirty-eight tools that were
        // working — the defect `Findings.sort` carries a comment
        // about, in the commit that added this node to close a
        // different honesty gap. `Findings.pending` exists for it.
        val running = Findings.pending(results)
        val byKind = (results - running.toSet()).groupBy { Findings.kindOf(it) }
        byKind[Findings.Kind.HIT].orEmpty().forEach { r ->
            val toolId = "tool:${r.toolId}"
            nodes.getOrPut(toolId) { Node(toolId, r.toolName, 1) }
            // getOrPut deduplicates the node and `add` does not
            // deduplicate the edge, so two HIT rows from one tool
            // drew the same line twice and doubled its pull in the
            // layout.
            if (links.none { it.source == seedId && it.target == toolId }) {
                links.add(Link(seedId, toolId))
            }

            val lines = ResultFormatter.format(r.toolId, r.rawOutput).lines
            lines.take(GRAPH_LEAVES).forEach { raw ->
                // Ellipsised, and the id carries the whole line.
                //
                // The id was the truncated label, which is the
                // collision the comment below says was fixed: two
                // LinkedIn profile URLs agreeing for 48 characters
                // merged into one node and the second finding's edge
                // pointed at the first one's text.
                val label = if (raw.length <= 48) raw else raw.take(47) + "\u2026"
                if (label.isNotBlank()) {
                    // The label itself, not its 32-bit hash.
                    //
                    // These labels are third-party page titles, bios
                    // and WHOIS fields. String.hashCode collides
                    // trivially and on purpose — "Aa" and "BB" have
                    // the same hash, and so do "admin@Aa.com" and
                    // "admin@BB.com" — so two distinct findings were
                    // silently merged into one node and the second
                    // one's edge pointed at the first one's text.
                    val itemId = "item:$raw"
                    nodes.getOrPut(itemId) { Node(itemId, label, 2) }
                    if (links.none { it.source == toolId && it.target == itemId }) {
                        links.add(Link(toolId, itemId))
                    }
                }
            }
            // A cap that announces itself, which is what Listing is
            // for and what this package's rule 4 requires. The graph
            // drew twelve of sixty subdomains and said nothing, on
            // the screen its own comment calls the one most likely
            // to be screenshotted.
            if (lines.size > GRAPH_LEAVES) {
                // Two of the three insertions above were given a
                // `links.none` guard and this one, eight lines below
                // them, was not — so two HIT rows from one tool drew
                // this edge twice and doubled its pull, which is the
                // consequence the first guard's comment describes.
                //
                // The node is per row, too. `more:crtsh` was shared
                // between them, `getOrPut` kept the first, and the
                // second row's twenty-eight were never mentioned
                // anywhere.
                val moreId = "more:${r.toolId}:${r.id}"
                nodes.getOrPut(moreId) {
                    Node(moreId, "…and ${lines.size - GRAPH_LEAVES} more", 2)
                }
                if (links.none { it.source == toolId && it.target == moreId }) {
                    links.add(Link(toolId, moreId))
                }
            }
        }

        // What was asked and could not answer.
        //
        // Keeping only HITs made a case where ten sources were all
        // refused pixel-identical to one where ten sources looked
        // and found nothing — and to one that was never run. The
        // three-way split exists so the picture can tell them
        // apart, and the one picture people share was throwing it
        // away. Drawn as a single node rather than ten, because the
        // graph is about what connects to what and this is a
        // caveat on all of it.
        val absent = byKind[Findings.Kind.ABSENT].orEmpty()
        val unreachable = byKind[Findings.Kind.UNREACHABLE].orEmpty()
        if (absent.isNotEmpty() || unreachable.isNotEmpty() || running.isNotEmpty()) {
            val note = buildList {
                if (absent.isNotEmpty()) add("${absent.size} looked and found nothing")
                if (unreachable.isNotEmpty()) add("${unreachable.size} could not be asked")
                // Said, rather than left out: a graph drawn mid-search
                // is a graph that will look different in a minute, and
                // the reader should know that before they screenshot
                // it.
                if (running.isNotEmpty()) add("${running.size} still running")
            }.joinToString(" · ")
            val id = "note:coverage"
            nodes[id] = Node(id, note, 3)
            links.add(Link(seedId, id))
        }
        return Graph(nodes.values.toList(), links)
    }

    /** Leaves drawn per tool before the count takes over. */
    private const val GRAPH_LEAVES = 12
}
