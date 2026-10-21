package com.osint.unified.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Turns a tool's raw (usually NDJSON) output into a concise human summary:
 * a headline (e.g. "12 subdomains") plus a list of lines. Parsing is entirely
 * defensive — any malformed line is skipped and any failure falls back to the
 * raw text so the user always sees something.
 */
object ResultFormatter {

    data class Summary(val headline: String, val lines: List<String>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun format(toolId: String, raw: String): Summary {
        if (raw.isBlank()) return Summary("No output", emptyList())
        return try {
            when (toolId) {
                "subfinder" -> ndjson(raw) { it.str("host") ?: it.str("input") ?: "" }
                    .let { Summary("${it.size} subdomains", it) }

                "httpx" -> ndjson(raw) { o ->
                    val url = o.str("url") ?: o.str("input") ?: ""
                    val status = o.str("status_code")?.let { "[$it]" } ?: ""
                    val title = o.str("title")?.let { " $it" } ?: ""
                    "$url $status$title".trim()
                }.let { Summary("${it.size} live hosts", it) }

                "nuclei" -> ndjson(raw) { o ->
                    val info = o["info"] as? JsonObject
                    val name = info?.str("name") ?: o.str("template-id") ?: "finding"
                    val severity = info?.str("severity") ?: "info"
                    val at = o.str("matched-at") ?: o.str("host") ?: ""
                    "[$severity] $name  $at".trim()
                }.let { Summary("${it.size} findings", it) }

                "trufflehog" -> ndjson(raw) { o ->
                    val detector = o.str("DetectorName") ?: "secret"
                    val verified = (o["Verified"] as? JsonPrimitive)?.booleanOrNull == true
                    (if (verified) "[VERIFIED] " else "") + detector
                }.let { Summary("${it.size} secrets", it) }

                "overpass_turbo" -> {
                    val elements = runCatching {
                        json.parseToJsonElement(raw).jsonObject["elements"]?.jsonArray
                    }.getOrNull() ?: JsonArray(emptyList())
                    val names = elements.mapNotNull { el ->
                        val tags = (el as? JsonObject)?.get("tags") as? JsonObject
                        val name = tags?.str("name")
                        val kind = tags?.str("amenity") ?: tags?.str("man_made")
                        when {
                            name != null && kind != null -> "$name ($kind)"
                            name != null -> name
                            kind != null -> kind
                            else -> null
                        }
                    }
                    Summary("${names.size} nearby features", names)
                }

                else -> fallback(raw)
            }
        } catch (e: Exception) {
            fallback(raw)
        }
    }

    private fun fallback(raw: String): Summary {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(40).toList()
        return Summary("${lines.size} lines", lines)
    }

    private inline fun ndjson(raw: String, transform: (JsonObject) -> String): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                runCatching { transform(json.parseToJsonElement(line).jsonObject).ifBlank { null } }
                    .getOrNull()
            }
            .toList()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
