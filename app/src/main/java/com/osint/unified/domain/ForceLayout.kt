package com.osint.unified.domain

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A force-directed layout, computed here rather than in a browser.
 *
 * The graph used to be drawn by D3 in a WebView, with the node labels —
 * which are text taken verbatim from third-party tool output: page titles,
 * profile bios, WHOIS fields — pasted straight into a `<script>` block. A
 * label containing `</script>` closed it and ran whatever followed, with
 * network access, inside a page holding the whole case graph. In a tool
 * whose subjects are by definition people who might want to do that, it was
 * the most dangerous line in the codebase.
 *
 * Computing the layout natively removes the WebView, the JavaScript, the
 * injection and the request to a CDN that announced every time a graph was
 * opened. It is also about sixty lines.
 */
object ForceLayout {

    data class Placed(val id: String, val label: String, val group: Int, val x: Float, val y: Float)
    data class Edge(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)
    data class Layout(val nodes: List<Placed>, val edges: List<Edge>)

    /**
     * Fruchterman–Reingold with a fixed seed, so the same graph always comes
     * out the same way round and the picture does not reshuffle itself on
     * every recomposition.
     */
    fun compute(
        graph: GraphBuilder.Graph,
        width: Float,
        height: Float,
        iterations: Int = 300
    ): Layout {
        val n = graph.nodes.size
        if (n == 0) return Layout(emptyList(), emptyList())

        val index = graph.nodes.withIndex().associate { (i, node) -> node.id to i }
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val random = Random(seed = 20261022L)
        // Start on a ring rather than at random: a circle untangles far
        // faster than a cloud, and the seed node is pinned at the centre.
        graph.nodes.forEachIndexed { i, _ ->
            val angle = (2.0 * Math.PI * i / n).toFloat()
            val r = width.coerceAtMost(height) * 0.35f
            xs[i] = width / 2 + r * kotlin.math.cos(angle) + random.nextFloat()
            ys[i] = height / 2 + r * kotlin.math.sin(angle) + random.nextFloat()
        }
        val seedIndex = index["seed"] ?: 0
        xs[seedIndex] = width / 2
        ys[seedIndex] = height / 2

        val area = width * height
        val k = sqrt(area / n)
        var temperature = width / 8f
        val cooling = temperature / (iterations + 1)

        val dx = FloatArray(n)
        val dy = FloatArray(n)

        repeat(iterations) {
            java.util.Arrays.fill(dx, 0f)
            java.util.Arrays.fill(dy, 0f)

            // Repulsion between every pair.
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var ddx = xs[i] - xs[j]
                    var ddy = ys[i] - ys[j]
                    var dist = sqrt(ddx * ddx + ddy * ddy)
                    if (dist < 0.01f) {
                        ddx = 0.01f; ddy = 0.01f; dist = 0.014f
                    }
                    val force = k * k / dist
                    val ux = ddx / dist
                    val uy = ddy / dist
                    dx[i] += ux * force; dy[i] += uy * force
                    dx[j] -= ux * force; dy[j] -= uy * force
                }
            }

            // Attraction along edges.
            graph.links.forEach { link ->
                val a = index[link.source] ?: return@forEach
                val b = index[link.target] ?: return@forEach
                var ddx = xs[a] - xs[b]
                var ddy = ys[a] - ys[b]
                var dist = sqrt(ddx * ddx + ddy * ddy)
                if (dist < 0.01f) dist = 0.01f
                val force = dist * dist / k
                val ux = ddx / dist
                val uy = ddy / dist
                dx[a] -= ux * force; dy[a] -= uy * force
                dx[b] += ux * force; dy[b] += uy * force
            }

            for (i in 0 until n) {
                val disp = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (disp > 0.001f) {
                    val limited = disp.coerceAtMost(temperature)
                    xs[i] += dx[i] / disp * limited
                    ys[i] += dy[i] / disp * limited
                }
                xs[i] = xs[i].coerceIn(24f, width - 24f)
                ys[i] = ys[i].coerceIn(24f, height - 24f)
            }
            // The seed stays put so the picture has a fixed anchor.
            xs[seedIndex] = width / 2
            ys[seedIndex] = height / 2
            temperature -= cooling
        }

        val placed = graph.nodes.mapIndexed { i, node ->
            Placed(node.id, node.label, node.group, xs[i], ys[i])
        }
        val edges = graph.links.mapNotNull { link ->
            val a = index[link.source] ?: return@mapNotNull null
            val b = index[link.target] ?: return@mapNotNull null
            Edge(xs[a], ys[a], xs[b], ys[b])
        }
        return Layout(placed, edges)
    }
}
