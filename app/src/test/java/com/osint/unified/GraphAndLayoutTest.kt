package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.ForceLayout
import com.osint.unified.domain.GraphBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link graph and the layout under it, neither of which had a
 * test of any kind.
 *
 * The graph is, by its own comment, "the screen most likely to be
 * screenshotted", so what it leaves out matters more here than
 * anywhere else in the app.
 */
class GraphAndLayoutTest {

    private var next = 1L

    private fun row(tool: String, output: String, status: String = "success") = ResultRow(
        id = next++,
        investigationId = 1,
        toolId = tool,
        toolName = tool,
        input = "example.com",
        status = status,
        rawOutput = output,
        startedAt = 0L,
        finishedAt = 1L
    )

    /**
     * Ten refused sources and ten genuine absences drew the same
     * picture as each other, and as a case nobody had run.
     */
    @Test
    fun aCaseWhereNothingCouldBeAskedDoesNotLookLikeACaseWithNoFindings() {
        val refused = GraphBuilder.build(
            "example.com",
            listOf(
                row("crtsh", "crt.sh could not be reached."),
                row("dns", "The resolver did not answer.")
            )
        )
        val absent = GraphBuilder.build(
            "example.com",
            listOf(
                row("crtsh", "No certificates found for example.com."),
                row("dns", "No DNS records found.")
            )
        )
        val nothing = GraphBuilder.build("example.com", emptyList())

        // All three used to be one seed node and nothing else.
        assertTrue(refused.nodes.size > nothing.nodes.size)
        assertTrue(absent.nodes.size > nothing.nodes.size)
        assertEquals(1, nothing.nodes.size)
        val refusedNote = refused.nodes.map { it.label }.first { it != "example.com" }
        val absentNote = absent.nodes.map { it.label }.first { it != "example.com" }
        assertTrue(refusedNote, refusedNote.contains("could not be asked"))
        assertTrue(absentNote, absentNote.contains("found nothing"))
    }

    /** A cap that announces itself, which is this project's rule 4. */
    @Test
    fun aToolWithMoreLeavesThanTheCapSaysHowManyAreMissing() {
        val lines = (1..40).joinToString("\n") { "host$it.example.com" }
        val graph = GraphBuilder.build("example.com", listOf(row("crtsh", lines)))
        val labels = graph.nodes.map { it.label }
        assertTrue(labels.toString(), labels.any { it.startsWith("…and ") })
    }

    /**
     * Two findings that agree for their first 48 characters are two
     * findings. The id was the truncated label, which is the exact
     * collision the comment above it says was fixed.
     */
    @Test
    fun twoLongFindingsThatShareAPrefixAreTwoNodes() {
        val a = "https://www.linkedin.com/in/john-smith-1111111111"
        val b = "https://www.linkedin.com/in/john-smith-2222222222"
        val graph = GraphBuilder.build("john smith", listOf(row("websearch", "$a\n$b")))
        val leaves = graph.nodes.filter { it.group == 2 }
        assertEquals(leaves.map { it.id }.toString(), 2, leaves.size)
    }

    /** One tool returning two hits draws one edge to that tool. */
    @Test
    fun oneToolIsOneEdgeHoweverManyRowsItReturned() {
        val graph = GraphBuilder.build(
            "example.com",
            listOf(row("crtsh", "a.example.com"), row("crtsh", "b.example.com"))
        )
        val toSeed = graph.links.count { it.source == "seed" && it.target == "tool:crtsh" }
        assertEquals(1, toSeed)
    }

    /**
     * The third edge insertion, which the dedup fix missed.
     *
     * Two rows from one tool, each over the twelve-leaf cap, shared
     * the node id `more:crtsh`: `getOrPut` kept the first label, so
     * the second row's overflow was never mentioned, and
     * `links.add` ran twice, so the edge was drawn twice and pulled
     * twice as hard.
     */
    @Test
    fun eachRowsOverflowIsItsOwnNodeAndItsOwnEdge() {
        val many = (1..20).joinToString("\n") { "a$it.example.com" }
        val other = (1..40).joinToString("\n") { "b$it.example.com" }
        val graph = GraphBuilder.build("example.com", listOf(row("crtsh", many), row("crtsh", other)))
        val more = graph.nodes.filter { it.id.startsWith("more:") }
        assertEquals(2, more.size)
        assertTrue(more.any { it.label.contains("8 more") })
        assertTrue(more.any { it.label.contains("28 more") })
        more.forEach { n ->
            assertEquals(1, graph.links.count { it.source == "tool:crtsh" && it.target == n.id })
        }
    }

    /**
     * A tool still working is not a tool that could not be asked.
     *
     * `Findings.kindOf` maps "running" to UNREACHABLE through its
     * `else`, so a graph opened during a search said "N could not
     * be asked" about N tools that were mid-flight — the same
     * mistake `Findings.sort` carries a comment about avoiding.
     */
    @Test
    fun toolsStillRunningAreNotCountedAsRefused() {
        val graph = GraphBuilder.build(
            "example.com",
            listOf(
                row("crtsh", "a.example.com"),
                row("shodan", "", status = "running"),
                row("censys", "", status = "running")
            )
        )
        val note = graph.nodes.firstOrNull { it.id == "note:coverage" }
        assertTrue(note != null)
        assertFalse(note!!.label.contains("could not be asked"))
        assertTrue(note.label.contains("2 still running"))
    }

    // ---- the layout ----

    private fun graph(n: Int): GraphBuilder.Graph {
        val nodes = (0 until n).map {
            GraphBuilder.Node(if (it == 0) "seed" else "n$it", "node $it", if (it == 0) 0 else 2)
        }
        val links = (1 until n).map { GraphBuilder.Link("seed", "n$it") }
        return GraphBuilder.Graph(nodes, links)
    }

    /** The same graph twice is the same picture twice. */
    @Test
    fun theLayoutIsDeterministic() {
        val a = ForceLayout.compute(graph(12), 800f, 600f)
        val b = ForceLayout.compute(graph(12), 800f, 600f)
        assertEquals(a.nodes.size, b.nodes.size)
        a.nodes.zip(b.nodes).forEach { (x, y) ->
            assertEquals(x.id, y.id)
            assertEquals(x.x, y.x, 0f)
            assertEquals(x.y, y.y, 0f)
        }
    }

    /** And it survives the shapes that divide by zero. */
    @Test
    fun theDegenerateGraphsDoNotProduceNaN() {
        listOf(0, 1, 2).forEach { n ->
            val laid = ForceLayout.compute(graph(n), 800f, 600f)
            assertEquals(n, laid.nodes.size)
            laid.nodes.forEach {
                assertFalse("${it.id} x", it.x.isNaN())
                assertFalse("${it.id} y", it.y.isNaN())
            }
        }
        // Two nodes, no links, a canvas of nothing.
        val tiny = ForceLayout.compute(graph(2), 0f, 0f)
        tiny.nodes.forEach {
            assertFalse(it.x.isNaN())
            assertFalse(it.y.isNaN())
        }
    }

    /** A graph with no seed does not get an arbitrary node pinned. */
    @Test
    fun aGraphWithoutASeedIsNotPinned() {
        val nodes = listOf(
            GraphBuilder.Node("a", "a", 2),
            GraphBuilder.Node("b", "b", 2)
        )
        val laid = ForceLayout.compute(GraphBuilder.Graph(nodes, emptyList()), 800f, 600f)
        val centred = laid.nodes.count { it.x == 400f && it.y == 300f }
        assertEquals(0, centred)
    }
}
