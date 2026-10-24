package com.osint.unified.domain.executor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * The one thing every MediaWiki caller has to get right.
 *
 * The action API — Wikipedia, Wikidata, Wikimedia Commons, all of
 * them — answers **HTTP 200** with `{"error": {"code": …, "info": …}}`
 * and no result block for a parameter error, a `maxlag` or read-only
 * condition, a blocked request or a rate limit. Every `getBody` in
 * this package throws only on a non-2xx, so the refusal arrives as a
 * successful response whose result key is simply missing — and a
 * missing result key reads, in four different places in this
 * codebase, as "there is nothing".
 *
 * That produced "No Wikipedia places within 10 km." for a refusal,
 * "Wikidata: nothing matching 'Jane Doe'" for a maxlag, "no account"
 * for a user whose contributions could not be read, and "No
 * geotagged imagery found near 51.50735, -0.12776" for a London
 * street corner with thousands of photographs of it. `Findings`
 * classifies all four as ABSENT and counts them as sources that
 * looked, which is the one distinction this app exists to keep.
 *
 * One of the four was fixed in isolation and the other three were
 * not, so the test lives here now and every caller goes through it.
 */
internal object MediaWiki {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a MediaWiki action-API body, raising on a stated error
     * and on a body that is not an object at all.
     *
     * [what] names the call, so the message says which request was
     * refused rather than "Wikipedia said no".
     */
    fun body(raw: String, what: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: throw IOException("$what: the answer was not a MediaWiki response")
        (root["error"] as? JsonObject)?.let { err ->
            val code = (err["code"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
            val info = (err["info"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            throw IOException(
                "$what: refused ($code)" + if (info.isBlank()) "" else " — $info"
            )
        }
        // `warnings` is not an error: MediaWiki attaches them to
        // perfectly good answers, usually about a deprecated
        // parameter, and treating one as a refusal would turn a
        // working source off.
        return root
    }

    /**
     * The `query` block, or a raised failure.
     *
     * A 200 with neither `error` nor `query` is a shape this code
     * does not understand, which is also not an absence.
     */
    fun query(raw: String, what: String): JsonObject {
        val root = body(raw, what)
        return root["query"]?.jsonObject
            ?: throw IOException("$what: the answer carried no results block")
    }
}
