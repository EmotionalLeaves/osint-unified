package com.osint.unified.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A node in the OSINT Framework directory tree (bundled from arf.json). */
data class ArfNode(
    val name: String,
    val url: String?,
    val children: List<ArfNode>
) {
    val isFolder: Boolean get() = children.isNotEmpty()
}

/**
 * Loads the full OSINT Framework resource directory (hundreds of tools in a
 * category tree) from bundled assets/arf.json, so the whole framework is
 * browsable and searchable offline inside the app.
 */
object OsintFramework {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var cached: ArfNode? = null

    fun load(context: Context): ArfNode? {
        cached?.let { return it }
        return try {
            val text = context.assets.open("arf.json").bufferedReader().use { it.readText() }
            val arf = parse(json.parseToJsonElement(text) as JsonObject)
            // Curated catalogues shipped alongside the framework are folded in
            // as extra top-level categories, so one directory covers them all.
            val extras = CATALOGUES.mapNotNull { (asset, label) ->
                markdownCatalogue(context, asset, label)
            }
            val root = if (extras.isEmpty()) arf
            else ArfNode(arf.name, arf.url, arf.children + extras)
            cached = root
            root
        } catch (e: Exception) {
            null
        }
    }

    private val CATALOGUES = listOf(
        "awesome-osint.md" to "awesome-osint",
        "osint-stuff.md" to "osint_stuff_tool_collection",
        "osint-toolbox.md" to "OSINT Toolbox"
    )

    /**
     * Turns a curated markdown tool list into a browsable category tree.
     * Markdown headings become folders and `[name](url)` links become leaves,
     * which is enough structure for these catalogues — they are all
     * heading-grouped link lists.
     */
    private fun markdownCatalogue(context: Context, asset: String, label: String): ArfNode? {
        val text = runCatching {
            context.assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { it.length > 200 } ?: return null

        val linkRegex = Regex("""\[([^\]\[]{1,120})\]\((https?://[^)\s]+)\)""")
        val headingRegex = Regex("""^#{2,4}\s+(.+?)\s*#*$""")
        val sections = LinkedHashMap<String, MutableList<ArfNode>>()
        val seen = HashSet<String>()
        var current = "General"

        text.lineSequence().forEach { line ->
            val heading = headingRegex.find(line.trim())?.groupValues?.get(1)
            if (heading != null) {
                // Strip any markdown link syntax out of the heading itself.
                current = heading.replace(linkRegex, "$1").trim().take(60)
                return@forEach
            }
            linkRegex.findAll(line).forEach { m ->
                val name = m.groupValues[1].trim()
                val url = m.groupValues[2].trimEnd('.', ',', ')')
                // Skip badges, anchors and the catalogue's own scaffolding.
                if (name.isBlank() || url.contains("shields.io") || url.contains("badge")) return@forEach
                if (name.startsWith("!") || name.length < 2) return@forEach
                if (!seen.add(url)) return@forEach
                sections.getOrPut(current) { mutableListOf() }.add(ArfNode(name, url, emptyList()))
            }
        }
        val children = sections.entries
            .filter { it.value.isNotEmpty() }
            .map { (heading, links) -> ArfNode(heading, null, links) }
        if (children.isEmpty()) return null
        val total = children.sumOf { it.children.size }
        return ArfNode("$label ($total)", null, children)
    }

    private fun parse(o: JsonObject): ArfNode {
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        val url = (o["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val children = (o["children"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::parse) } ?: emptyList()
        return ArfNode(name, url, children)
    }

    /** Leaf resources (name + url) merged from every top-level category whose
     *  name matches any of [names] (case-insensitive, substring), deduped. */
    fun categoryLeaves(context: Context, names: List<String>): List<Pair<String, String>> {
        val root = load(context) ?: return emptyList()
        val byUrl = LinkedHashMap<String, String>() // url -> name, dedupes
        root.children.forEach { c ->
            if (names.any { c.name.contains(it, ignoreCase = true) }) {
                leaves(c).forEach { (name, url, _) ->
                    // OSINT Framework marks registration-required with (R) and
                    // paid with $ — exclude those so searches only include
                    // resources usable with no account.
                    if (!name.contains("(R)") && !name.contains("(R*)") && !name.contains("$")) {
                        byUrl.putIfAbsent(url, name)
                    }
                }
            }
        }
        return byUrl.map { (url, name) -> name to url }
    }

    /** All leaf resources (name + url) under a node, for search. */
    fun leaves(node: ArfNode, path: String = ""): List<Triple<String, String, String>> {
        val here = if (path.isBlank()) node.name else "$path › ${node.name}"
        val out = mutableListOf<Triple<String, String, String>>()
        if (node.url != null && node.children.isEmpty()) out.add(Triple(node.name, node.url, path))
        node.children.forEach { out.addAll(leaves(it, here)) }
        return out
    }
}
