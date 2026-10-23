package com.osint.unified.data

import com.osint.unified.network.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One way of asking OpenStreetMap a question.
 *
 * Six places in this app posted their own Overpass query to one
 * hardcoded server and each of them got the same two things wrong.
 *
 * The first is that Overpass answers HTTP 200 with a body that is not
 * JSON. Under load it writes `runtime error: Query timed out in
 * "query" at line 1`, or an HTML holding page, with a success status
 * on the front — so a `parseToJsonElement` wrapped in runCatching
 * came out null, null was treated as an empty result set, and the app
 * printed "No military-tagged sites within 25 km." A twenty-five
 * kilometre `nwr[military]` query is precisely the one most likely to
 * time out, which is why the person who reported that these have
 * "never worked" was right.
 *
 * The second is that there is more than one server. overpass-api.de
 * is the busiest instance of a volunteer-run service and spends a
 * good part of the day at capacity; kumi.systems and private.coffee
 * run the same software over the same data and were sitting idle.
 * Probed together, the main server answered a trivial query and
 * failed an ordinary one in the same minute while both mirrors
 * answered both.
 *
 * So: ask each in turn, know the difference between "nothing is
 * there" and "nobody answered", and say which.
 */
internal object Overpass {

    sealed interface Answer {
        /** The server answered, and this is what it holds. */
        data class Ok(
            val elements: JsonArray,
            val from: String,
            /** The body as it arrived, for a caller with its own parser. */
            val body: String
        ) : Answer

        /** Nobody answered, and this is as far as it got. */
        data class Unreachable(val why: String) : Answer
    }

    /**
     * Mirrors, in the order they are tried.
     *
     * The main instance first because it is the reference one and the
     * one OSM asks people to use; the others because a volunteer
     * service at capacity is not an answer and a layer that is dark
     * four afternoons a week is not a layer.
     */
    private val MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    /**
     * Overpass asks to be told who is calling, and unlike the hosts
     * this app investigates it is not the target of anything.
     */
    private const val AGENT =
        "OSINT-Unified/1.0 (+https://github.com/EmotionalLeaves/osint-unified)"

    private val FORM = "application/x-www-form-urlencoded".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The whole answer: elements, or a reason there are none. */
    fun query(ql: String, client: OkHttpClient = Http.feedClient): Answer {
        var why = "no Overpass server was reached"
        val body = "data=" + java.net.URLEncoder.encode(ql, "UTF-8")
        for (mirror in MIRRORS) {
            val attempt = runCatching {
                val request = Request.Builder()
                    .url(mirror)
                    .header("User-Agent", AGENT)
                    .header("Accept", "application/json")
                    .post(body.toRequestBody(FORM))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw java.io.IOException("HTTP ${resp.code}")
                    }
                    text
                }
            }
            val text = attempt.getOrElse {
                why = "${host(mirror)}: ${it.message ?: "no answer"}"
                continue
            }
            val elements = elementsOf(text)
            if (elements == null) {
                // A 200 that is not the answer. Overpass puts its own
                // error in the body, so the first line of it is the
                // most useful thing to carry forward.
                why = "${host(mirror)}: " +
                    text.lineSequence().firstOrNull { it.isNotBlank() }
                        ?.trim()?.take(120).orEmpty().ifBlank { "not a JSON answer" }
                continue
            }
            return Answer.Ok(elements, host(mirror), text)
        }
        return Answer.Unreachable(why)
    }

    /**
     * The same, as the raw body, for callers with their own parser.
     *
     * Throws when nobody answered, which is what the map layers
     * already expect and already handle.
     */
    fun raw(ql: String, client: OkHttpClient = Http.feedClient): String =
        when (val answer = query(ql, client)) {
            is Answer.Ok -> answer.body
            is Answer.Unreachable -> throw java.io.IOException(answer.why)
        }

    /**
     * The elements array, or null when this is not an Overpass answer.
     *
     * A well-formed answer with no matches has `"elements": []`, which
     * is an empty array and not a null — the distinction the callers
     * were losing.
     */
    private fun elementsOf(text: String): JsonArray? = runCatching {
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        o["elements"] as? JsonArray
    }.getOrNull()

    private fun host(url: String) = url.substringAfter("://").substringBefore('/')
}
