package com.osint.unified.domain.executor

import com.osint.unified.network.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sources that answer about a handle, and two that answer about an
 * address.
 *
 * A username search ran eight sources that fetch, and every one of
 * them answers the same question — does an account with this name
 * exist here. That is worth knowing once and is thin as a dossier.
 * What these add is the other question: when a handle does exist
 * somewhere, what has its owner written down about themselves. A
 * self-declared city, employer, real name or linked profile is a
 * lead; "account exists" is a tick.
 *
 * Every endpoint here was probed live from CI before being wired in.
 * None takes a key, none needs an account, and each returns a
 * bounded response.
 *
 * Everything in these records is self-declared except the dates, and
 * the output says so, because a profile field is a claim its subject
 * made about themselves and nothing more.
 */
class HandleApis(
    private val client: OkHttpClient = Http.client
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------
    // Handles
    // ------------------------------------------------------------------

    /**
     * Chess.com's published-data API.
     *
     * Chess is played under a handle by a very large number of
     * people, and the profile carries a free-text location that is
     * usually a real city, plus a Twitch link when the player
     * streams — which is a pivot to another identity rather than
     * another tick.
     */
    fun chess(username: String): Result<String> = runCatching {
        val handle = clean(username)
        val body = getOrNull("https://api.chess.com/pub/player/$handle")
            ?: return@runCatching "No Chess.com account named \"$handle\"."
        val o = json.parseToJsonElement(body).jsonObject
        buildString {
            append("Chess.com account \"$handle\" exists.\n")
            o.str("name")?.let { append("Declared name: $it\n") }
            o.str("title")?.let { append("Title: $it\n") }
            o.str("location")?.let { append("Declared location: $it\n") }
            o.str("country")?.substringAfterLast('/')?.let { append("Country: $it\n") }
            o.long("joined")?.let { append("Joined: ${day(it * 1000)}\n") }
            o.long("last_online")?.let { append("Last online: ${day(it * 1000)}\n") }
            o.int("followers")?.let { append("Followers: $it\n") }
            o.str("twitch_url")?.let { append("Streams at: $it\n") }
            o.str("url")?.let { append("\n$it\n") }
            append(DECLARED)
        }
    }

    /**
     * Lichess, where the profile is written by its owner and the
     * last-seen timestamp is not.
     *
     * The links field is the useful one: players put their other
     * accounts in it themselves.
     */
    fun lichess(username: String): Result<String> = runCatching {
        val handle = clean(username)
        val body = getOrNull("https://lichess.org/api/user/$handle")
            ?: return@runCatching "No Lichess account named \"$handle\"."
        val o = json.parseToJsonElement(body).jsonObject
        if (o["id"] == null) {
            return@runCatching "No Lichess account named \"$handle\"."
        }
        val profile = o["profile"] as? JsonObject
        buildString {
            append("Lichess account \"$handle\" exists.\n")
            profile.str("realName")?.let { append("Declared name: $it\n") }
            profile.str("country")?.let { append("Declared country: $it\n") }
            profile.str("location")?.let { append("Declared location: $it\n") }
            profile.str("bio")?.take(300)?.let { append("Bio: $it\n") }
            profile.str("links")?.let { append("Declared links:\n$it\n") }
            o.long("createdAt")?.let { append("Joined: ${day(it)}\n") }
            o.long("seenAt")?.let { append("Last seen: ${day(it)}\n") }
            if (o["disabled"] == JsonPrimitive(true)) append("Account is closed.\n")
            append("\nhttps://lichess.org/@/$handle\n")
            append(DECLARED)
        }
    }

    /**
     * Codeforces, where competitors fill in where they work or study.
     *
     * Of the four handle sources here this is the one most likely to
     * hand over an employer or a university, because the ranking
     * table shows it and people want it filled in.
     */
    fun codeforces(username: String): Result<String> = runCatching {
        val handle = clean(username)
        val body = getOrNull(
            "https://codeforces.com/api/user.info?handles=" + enc(handle)
        ) ?: return@runCatching "No Codeforces account named \"$handle\"."
        val root = json.parseToJsonElement(body).jsonObject
        if (root.str("status") != "OK") {
            return@runCatching "No Codeforces account named \"$handle\"."
        }
        val o = (root["result"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: return@runCatching "No Codeforces account named \"$handle\"."
        buildString {
            append("Codeforces account \"$handle\" exists.\n")
            val name = listOfNotNull(o.str("firstName"), o.str("lastName"))
                .joinToString(" ").trim()
            if (name.isNotBlank()) append("Declared name: $name\n")
            o.str("country")?.let { append("Declared country: $it\n") }
            o.str("city")?.let { append("Declared city: $it\n") }
            o.str("organization")?.let { append("Declared organisation: $it\n") }
            o.int("rating")?.let { append("Rating: $it\n") }
            o.long("registrationTimeSeconds")?.let { append("Registered: ${day(it * 1000)}\n") }
            o.long("lastOnlineTimeSeconds")?.let { append("Last online: ${day(it * 1000)}\n") }
            append("\nhttps://codeforces.com/profile/$handle\n")
            append(DECLARED)
        }
    }

    /**
     * Every Wikimedia wiki one account has touched.
     *
     * The app already asks OpenStreetMap what a handle has edited;
     * this is the same question put to Wikipedia and its sisters,
     * and the answer is a map of what somebody is interested in,
     * which languages they work in, and how long they have been at
     * it. Edit counts and registration dates are facts about the
     * account rather than claims by its owner, which makes this the
     * strongest of the handle sources.
     */
    fun wikimediaUser(username: String): Result<String> = runCatching {
        val handle = username.trim().removePrefix("@")
        val body = getOrNull(
            "https://meta.wikimedia.org/w/api.php?action=query&meta=globaluserinfo" +
                "&guiuser=" + enc(handle) +
                "&guiprop=merged%7Ceditcount%7Cgroups&format=json"
        ) ?: return@runCatching "Wikimedia could not be asked about \"$handle\"."
        val info = json.parseToJsonElement(body).jsonObject["query"]
            ?.jsonObject?.get("globaluserinfo")?.jsonObject
            ?: return@runCatching "Wikimedia could not be asked about \"$handle\"."
        if (info["missing"] != null) {
            return@runCatching "No Wikimedia account named \"$handle\" " +
                "(checked the global account register, which covers every " +
                "Wikipedia and sister project at once)."
        }
        val merged = (info["merged"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it.int("editcount") ?: 0) > 0 }
            .sortedByDescending { it.int("editcount") ?: 0 }
        buildString {
            append("Wikimedia account \"$handle\" exists on ${merged.size} ")
            append(if (merged.size == 1) "project" else "projects")
            append(" with edits.\n")
            info.str("home")?.let { append("Home wiki: $it\n") }
            info.int("editcount")?.let { append("Edits in total: $it\n") }
            info.str("registration")?.take(10)?.let { append("Registered: $it\n") }
            val groups = (info["groups"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (groups.isNotEmpty()) append("Global rights: ${groups.joinToString(", ")}\n")
            if (merged.isNotEmpty()) {
                append("\nWhere the edits are:\n")
                merged.take(12).forEach { w ->
                    append("• ${w.str("wiki") ?: "?"} — ${w.int("editcount") ?: 0} edits")
                    w.str("registration")?.take(10)?.let { append(", since $it") }
                    append("\n")
                }
                if (merged.size > 12) append("…and ${merged.size - 12} more projects.\n")
            }
            append("\nEdit counts and dates are the wikis' own records, not ")
            append("anything the account holder wrote about themselves.\n")
        }
    }

    /**
     * Codeberg, which is a Gitea, which is the Git host people move
     * to when they are deliberately not on GitHub.
     */
    fun codeberg(username: String): Result<String> = runCatching {
        val handle = clean(username)
        val body = getOrNull("https://codeberg.org/api/v1/users/" + enc(handle))
            ?: return@runCatching "No Codeberg account named \"$handle\"."
        val o = json.parseToJsonElement(body).jsonObject
        buildString {
            append("Codeberg account \"$handle\" exists.\n")
            o.str("full_name")?.let { append("Declared name: $it\n") }
            o.str("location")?.let { append("Declared location: $it\n") }
            o.str("description")?.take(300)?.let { append("Bio: $it\n") }
            o.str("website")?.let { append("Declared site: $it\n") }
            // Gitea hands out a noreply address for everyone; only a
            // real one is worth printing.
            o.str("email")?.takeIf { !it.endsWith("noreply.codeberg.org") }
                ?.let { append("Public email: $it\n") }
            o.str("created")?.take(10)?.let { append("Joined: $it\n") }
            o.int("followers_count")?.let { append("Followers: $it\n") }
            o.str("html_url")?.let { append("\n$it\n") }
            append(DECLARED)
        }
    }

    /**
     * Hugging Face, for a population the other handle sources miss
     * entirely.
     *
     * The organisations list is the useful part: it is often an
     * employer, stated by the employer rather than by the person.
     */
    fun huggingFace(username: String): Result<String> = runCatching {
        val handle = clean(username)
        val body = getOrNull("https://huggingface.co/api/users/" + enc(handle) + "/overview")
            ?: return@runCatching "No Hugging Face account named \"$handle\"."
        val o = json.parseToJsonElement(body).jsonObject
        buildString {
            append("Hugging Face account \"$handle\" exists.\n")
            o.str("fullname")?.let { append("Declared name: $it\n") }
            o.str("details")?.take(300)?.let { append("Bio: $it\n") }
            val counts = listOfNotNull(
                o.int("numModels")?.let { "$it models" },
                o.int("numDatasets")?.let { "$it datasets" },
                o.int("numSpaces")?.let { "$it spaces" }
            )
            if (counts.isNotEmpty()) append("Published: ${counts.joinToString(", ")}\n")
            val orgs = (o["orgs"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("fullname") ?: (it as? JsonObject)?.str("name") }
            if (orgs.isNotEmpty()) append("Organisations: ${orgs.joinToString(", ")}\n")
            append("\nhttps://huggingface.co/$handle\n")
            append(DECLARED)
        }
    }

    // ------------------------------------------------------------------
    // Addresses
    // ------------------------------------------------------------------

    /**
     * Libravatar, which is Gravatar for people who would rather not
     * use Gravatar.
     *
     * The app already asks Gravatar. Somebody who has deliberately
     * avoided a service owned by a blogging company and self-hosts
     * or federates instead will not be in it, and that is exactly
     * the person whose avatar is worth finding. `d=404` turns the
     * placeholder image into a clean no.
     */
    fun libravatar(email: String): Result<String> = runCatching {
        val address = email.trim().lowercase()
        if (!address.contains('@')) {
            return@runCatching "Give an email address."
        }
        val md5 = MessageDigest.getInstance("MD5").digest(address.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val url = "https://seccdn.libravatar.org/avatar/$md5?d=404&s=256"
        val code = headCode(url)
        when (code) {
            200 -> "Libravatar has an avatar published for this address.\n$url\n" +
                "Libravatar is federated, so the image may be served from the " +
                "address's own domain — which is itself a signal about where " +
                "the account lives."
            404 -> "No Libravatar avatar for this address."
            null -> "Libravatar could not be reached, so nothing is claimed either way."
            else -> "Libravatar answered HTTP $code, so nothing is claimed either way."
        }
    }

    /**
     * The Ubuntu keyserver, as a second PGP corpus.
     *
     * The app asks keys.openpgp.org, which only publishes an address
     * after the address has confirmed it — so a key there is good
     * evidence and a key missing from there means very little. This
     * one is the old SKS network and verifies nothing, which cuts
     * both ways: it holds keys the verified server never will, and a
     * hit proves somebody uploaded a key claiming this address, not
     * that they hold it. The output says which of those it is,
     * because the difference is the whole value of the first source.
     */
    fun ubuntuKeyserver(email: String): Result<String> = runCatching {
        val address = email.trim()
        if (!address.contains('@')) return@runCatching "Give an email address."
        val body = getOrNull(
            "https://keyserver.ubuntu.com/pks/lookup?op=index&options=mr&search=" +
                enc(address)
        ) ?: return@runCatching "The Ubuntu keyserver could not be reached, so " +
            "nothing is claimed either way."
        val keys = body.lineSequence().filter { it.startsWith("pub:") }.toList()
        val uids = body.lineSequence().filter { it.startsWith("uid:") }
            .mapNotNull { it.split(":").getOrNull(1) }
            .map { java.net.URLDecoder.decode(it, "UTF-8") }
            .distinct()
            .toList()
        if (keys.isEmpty()) {
            return@runCatching "No PGP key on the Ubuntu keyserver for $address."
        }
        buildString {
            append("${keys.size} PGP ")
            append(if (keys.size == 1) "key" else "keys")
            append(" on the Ubuntu keyserver mentioning $address.\n")
            keys.take(8).forEach { line ->
                val f = line.split(":")
                val fingerprint = f.getOrNull(1).orEmpty()
                val created = f.getOrNull(4)?.toLongOrNull()
                append("• ${fingerprint.takeLast(16)}")
                f.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { append("  $it-bit") }
                created?.let { append("  created ${day(it * 1000)}") }
                append("\n")
            }
            if (keys.size > 8) append("…and ${keys.size - 8} more.\n")
            if (uids.isNotEmpty()) {
                append("\nNames on those keys:\n")
                uids.take(10).forEach { append("  $it\n") }
            }
            append("\nThis keyserver does not verify that the address belongs ")
            append("to whoever uploaded the key — anyone can upload a key ")
            append("claiming any address. A key here is a claim; a key on ")
            append("keys.openpgp.org is a confirmed one.\n")
        }
    }

    // ------------------------------------------------------------------

    private val DECLARED =
        "\nEverything above except the dates is what the account holder " +
            "wrote about themselves, and nobody checked it.\n"

    private fun clean(v: String) = v.trim().removePrefix("@").substringAfterLast('/')

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun day(millis: Long): String = DAY.format(Instant.ofEpochMilli(millis))

    private fun JsonObject?.str(k: String) =
        (this?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject?.int(k: String) = (this?.get(k) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.long(k: String) = (this?.get(k) as? JsonPrimitive)?.longOrNull

    /** Null for a 404, which every one of these uses to mean "no such
     *  account"; thrown for anything else, which is a failure. */
    private fun getOrNull(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.defaultUserAgent)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404 || resp.code == 410) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }

    /** The status alone, for the one source whose answer is its status. */
    private fun headCode(url: String): Int? = runCatching {
        val request = Request.Builder().url(url).head()
            .header("User-Agent", Http.defaultUserAgent)
            .build()
        client.newCall(request).execute().use { it.code }
    }.getOrNull()

    private companion object {
        val DAY: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}
