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

        results.filter { it.status == "success" }.forEach { r ->
            val toolId = "tool:${r.toolId}"
            nodes.getOrPut(toolId) { Node(toolId, r.toolName, 1) }
            links.add(Link(seedId, toolId))

            ResultFormatter.format(r.toolId, r.rawOutput).lines.take(12).forEach { raw ->
                val label = raw.take(48)
                if (label.isNotBlank()) {
                    val itemId = "item:${label.hashCode()}"
                    nodes.getOrPut(itemId) { Node(itemId, label, 2) }
                    links.add(Link(toolId, itemId))
                }
            }
        }
        return Graph(nodes.values.toList(), links)
    }
}
