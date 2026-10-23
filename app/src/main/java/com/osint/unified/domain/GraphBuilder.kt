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
        results.filter { Findings.kindOf(it) == Findings.Kind.HIT }.forEach { r ->
            val toolId = "tool:${r.toolId}"
            nodes.getOrPut(toolId) { Node(toolId, r.toolName, 1) }
            links.add(Link(seedId, toolId))

            ResultFormatter.format(r.toolId, r.rawOutput).lines.take(12).forEach { raw ->
                val label = raw.take(48)
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
                    val itemId = "item:$label"
                    nodes.getOrPut(itemId) { Node(itemId, label, 2) }
                    links.add(Link(toolId, itemId))
                }
            }
        }
        return Graph(nodes.values.toList(), links)
    }
}
