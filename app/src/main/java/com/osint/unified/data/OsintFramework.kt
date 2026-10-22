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
            val root = parse(json.parseToJsonElement(text) as JsonObject)
            cached = root
            root
        } catch (e: Exception) {
            null
        }
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
