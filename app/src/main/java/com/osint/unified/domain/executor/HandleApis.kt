package com.osint.unified.domain.executor

import com.osint.unified.network.Http
import com.osint.unified.network.Http.bodyText
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
import java.util.Locale
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
        val o = profile(body, "Chess.com", "username")
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
        val body = getOrBody(
            "https://codeforces.com/api/user.info?handles=" + enc(handle)
        ) ?: return@runCatching "No Codeforces account named \"$handle\"."
        val root = json.parseToJsonElement(body).jsonObject
        // `status != "OK"` is also what a rate limit and a malformed
        // request look like, and printing the absence sentence for
        // those states that no such account exists on the strength of
        // the service declining to answer. Codeforces puts the reason
        // in `comment`; "not found" in it is the real absence.
        val status = root.str("status")
        if (status != "OK") {
            val why = root.str("comment").orEmpty()
            if (!why.contains("not found", ignoreCase = true)) {
                throw IOException(
                    "Codeforces did not answer" + if (why.isBlank()) "" else ": $why"
                )
            }
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
        ) ?: return@runCatching "No Wikimedia account named \"$handle\"."
        // Through MediaWiki, whose own doc says every caller does —
        // this one hand-rolled it and, worse, the sentence it fell
        // back to was classified as a FINDING, because "could not be
        // asked" was the one phrasing of that idea missing from
        // Findings.UNREACHABLE.
        val info = MediaWiki.query(body, "Wikimedia global user")["globaluserinfo"]?.jsonObject
            ?: throw IOException("Wikimedia returned no global user record")
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
        val o = profile(body, "Codeberg", "login")
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
        val o = profile(body, "Hugging Face", "user")
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
            .map { percentDecode(it) }
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
                // Field 6 carries the key's state and was never
                // read, so a revoked key was listed with no
                // qualification at all — on a source whose entire
                // framing is "a key here is a claim, not a
                // confirmation", silently promoting a revoked one is
                // the wrong direction.
                val flags = f.getOrNull(6).orEmpty()
                val state = buildList {
                    if (flags.contains('r')) add("REVOKED")
                    if (flags.contains('e')) add("EXPIRED")
                    if (flags.contains('d')) add("DISABLED")
                }
                if (state.isNotEmpty()) append("  ⚠ ${state.joinToString(", ")}")
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
    // Images
    // ------------------------------------------------------------------

    /**
     * What the server says the file is, without downloading it.
     *
     * An image search had two tools in it, one of which only builds
     * links. This is the cheapest real question there is about a
     * remote file and it costs headers rather than megabytes, which
     * matters on a phone: the true content type against the
     * extension the URL claims, the exact size, and when the host
     * last changed it.
     *
     * A type that disagrees with the extension is the interesting
     * case. A .jpg served as text/html is a redirect or a login
     * page, and a .png served as image/jpeg has been through
     * something that re-encoded it.
     */
    fun imageHead(url: String): Result<String> = runCatching {
        val target = url.trim()
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
            return@runCatching "Give the image's URL."
        }
        val request = Request.Builder().url(target).head()
            .header("User-Agent", Http.defaultUserAgent)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@runCatching "The host answered HTTP ${resp.code} for this " +
                    "image, so nothing was read from it."
            }
            val type = resp.header("Content-Type")?.substringBefore(';')?.trim()
            val length = resp.header("Content-Length")?.toLongOrNull()
            val modified = resp.header("Last-Modified")
            val claimed = target.substringAfterLast('.', "").substringBefore('?')
                .lowercase().takeIf { it.length in 2..5 }
            buildString {
                append("The host serves this file as ${type ?: "an unstated type"}")
                length?.let { append(", ${it / 1024} KB") }
                append(".\n")
                modified?.let { append("Last modified on the host: $it\n") }
                resp.header("ETag")?.let { append("ETag: $it\n") }
                if (type != null && claimed != null) {
                    val agrees = when (claimed) {
                        "jpg", "jpeg" -> type.endsWith("jpeg")
                        "png" -> type.endsWith("png")
                        "gif" -> type.endsWith("gif")
                        "webp" -> type.endsWith("webp")
                        "heic", "heif" -> type.contains("hei")
                        "tif", "tiff" -> type.endsWith("tiff")
                        else -> true
                    }
                    if (!agrees) {
                        append("\n⚠ The URL ends in .$claimed and the host calls it ")
                        append("$type. Those disagree: the link may redirect, or the ")
                        append("file may have been re-encoded, or it may not be an ")
                        append("image at all.\n")
                    }
                }
                if (type != null && !type.startsWith("image/")) {
                    append("\nThis is not being served as an image, so the metadata ")
                    append("tools will have nothing to read.\n")
                }
            }
        }
    }

    /**
     * How long this exact image has been online, and whether it has
     * changed.
     *
     * The app already asks the Wayback Machine about domains. Asked
     * about one image URL it answers a different and more useful
     * question: the earliest capture is a floor on the picture's
     * age, which is the cheapest way to contradict "this was taken
     * last week", and the digest column says whether the bytes are
     * the same ones. A digest that changes under a URL that did not
     * is an image quietly replaced.
     */
    fun imageHistory(url: String): Result<String> = runCatching {
        val target = url.trim().removePrefix("https://").removePrefix("http://")
        if (target.isBlank()) return@runCatching "Give the image's URL."
        // `getOrNull` returns null for a 400 as well as a 404, and
        // the CDX endpoint answers 400 for a URL it will not parse —
        // which was being printed as "never captured", an assertion
        // about the archive rather than about the request. A 404
        // from CDX is a genuine "no captures".
        val body = cdx(
            "https://web.archive.org/cdx/search/cdx?url=" + enc(target) +
                "&output=json&fl=timestamp,statuscode,digest&limit=$CDX_LIMIT"
        ) ?: return@runCatching "The Wayback Machine has never captured this image."
        val rows = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
            // An empty body is how CDX says "nothing"; a body that is
            // not JSON at all is the service misbehaving.
            ?: if (body.isBlank()) {
                return@runCatching "The Wayback Machine has never captured this image, " +
                    "which says nothing about its age — most images are never archived."
            } else {
                throw IOException("the Wayback CDX index answered in an unexpected shape")
            }
        if (rows.size <= 1) {
            return@runCatching "The Wayback Machine has never captured this image, " +
                "which says nothing about its age — most images are never archived."
        }
        val all = rows.drop(1).mapNotNull { row ->
            val cells = (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (cells == null || cells.size < 3) null else Triple(cells[0], cells[1], cells[2])
        }
        // The status column was asked for in `fl=` and never read,
        // so an archived 404 or redirect counted as a capture of the
        // image and its digest counted as the file having been
        // replaced — in the one tool whose purpose is noticing a
        // quietly swapped picture.
        val captures = all.filter { it.second.startsWith("2") || it.second == "-" }
        if (captures.isEmpty()) {
            return@runCatching if (all.isEmpty()) {
                "The Wayback Machine has never captured this image."
            } else {
                "The Wayback Machine has ${all.size} records for this URL and none " +
                    "of them is a successful capture — they are errors and " +
                    "redirects, so nothing can be said about the file itself."
            }
        }
        val first = captures.first()
        val last = captures.last()
        val digests = captures.map { it.third }.distinct()
        // `limit=40` returns the EARLIEST forty, so "the latest" was
        // the fortieth capture — years ago for a frequently archived
        // image — and "the bytes have not changed since 2011" was a
        // claim about the present made from the oldest rows.
        val capped = all.size >= CDX_LIMIT
        buildString {
            append("This image has been online since at least ${stamp(first.first)}.\n")
            if (capped) {
                append("The first $CDX_LIMIT captures were read, the last of them ")
                append("${stamp(last.first)} — the archive may hold more since.\n")
            } else {
                append("${captures.size} captures, the latest ${stamp(last.first)}.\n")
            }
            if (digests.size == 1) {
                append("\nEvery one of those captures has the same digest, so the ")
                append("bytes under this URL did not change between ")
                append("${stamp(first.first)} and ${stamp(last.first)}")
                append(if (capped) " — which is not a statement about now.\n" else ".\n")
            } else {
                append("\n⚠ ${digests.size} different digests across those captures: ")
                append("the file at this URL has been replaced at least ")
                append("${digests.size - 1} time")
                append(if (digests.size == 2) "" else "s")
                append(". What is served now is not what was served then.\n")
            }
            append("\nhttps://web.archive.org/web/${first.first}/$target\n")
            append("\nAn earliest capture is a floor on the age, not the age: it ")
            append("is when a crawler first saw it, which can be long after it ")
            append("was taken.\n")
        }
    }

    /**
     * `%XX` only, because HKP is not a form.
     *
     * `URLDecoder.decode` treats `+` as an encoded space, which HKP
     * does not — so a UID of `jane+keys@example.com` was printed as
     * `jane keys@example.com`: a corrupted identifier presented as
     * an observation about a person.
     */
    private fun percentDecode(v: String): String {
        if (!v.contains('%')) return v
        val out = StringBuilder(v.length)
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        while (i < v.length) {
            val c = v[i]
            val hex = if (c == '%' && i + 2 < v.length) {
                v.substring(i + 1, i + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                bytes.write(hex)
                i += 3
            } else {
                flush()
                out.append(c)
                i++
            }
        }
        flush()
        return out.toString()
    }

    /** How many CDX rows are asked for. They come earliest first. */
    private val CDX_LIMIT = 40

    /** CDX timestamps are yyyyMMddHHmmss. */
    private fun stamp(v: String): String =
        if (v.length >= 8) "${v.substring(0, 4)}-${v.substring(4, 6)}-${v.substring(6, 8)}"
        else v

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

    /**
     * Null for the codes that mean "no such account"; thrown for
     * anything else, which is a failure.
     *
     * 400 is in the list because of Codeforces: it answers an
     * unknown handle with 400 and a body saying status FAILED, not
     * with a 404. Treating that as an error made both of the "no
     * such account" branches in codeforces() unreachable and filed
     * every absence there as the source being broken.
     */
    private fun getOrNull(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.defaultUserAgent)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.bodyText().orEmpty()
            if (resp.code == 404 || resp.code == 410) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }

    /**
     * The profile object, or a raised failure.
     *
     * Three of these tools parsed the body and immediately printed
     * `<Service> account "x" exists.` — so any 2xx that happens to
     * parse as a JSON object, an error envelope included, produced
     * a stated existence claim about a person's account with no
     * fields under it. `lichess` next to them checks its record
     * first, which is the right shape. A false presence is the one
     * error this app's absence machinery cannot catch, because it
     * classifies as a finding correctly.
     */
    private fun profile(body: String, service: String, key: String): JsonObject {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw IOException("$service answered in an unexpected shape")
        if (o[key] == null) {
            throw IOException("$service answered without a $key, so the record was not read")
        }
        return o
    }

    /**
     * Like [getOrNull], but a 400 comes back with its body.
     *
     * 400 used to be folded into the same null as a 404, "because
     * of Codeforces: it answers an unknown handle with 400". It
     * does — and it also answers 400 for every other FAILED status,
     * including `"comment": "Call limit exceeded"`. `codeforces()`
     * has a block written specifically to tell those apart by
     * reading `comment`, and that block could only ever run on a
     * 2xx, where the status is always OK: the body carrying the
     * reason was thrown away three hundred lines earlier, before
     * anything parsed it. A rate-limited Codeforces therefore
     * printed "No Codeforces account named jsmith", which is an
     * established absence about a person.
     *
     * Rule 5, with the mechanism and the thing that defeats it in
     * one file. `cdx()` below was written for the same reason.
     */
    private fun getOrBody(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.defaultUserAgent)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.bodyText().orEmpty()
            if (resp.code == 404 || resp.code == 410) return null
            if (resp.code == 400) return body
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }

    /**
     * Like [getOrNull], but a 400 is a refusal rather than an
     * absence.
     *
     * The CDX index answers 400 for a URL it cannot parse, and
     * folding that into the same null as a 404 made "the Wayback
     * Machine has never captured this image" a statement the archive
     * had never made.
     */
    private fun cdx(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.defaultUserAgent)
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404 || resp.code == 410) return null
            if (!resp.isSuccessful) throw IOException("Wayback CDX HTTP ${resp.code}")
            return resp.body?.bodyText().orEmpty()
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
    // ------------------------------------------------------------------
    // A second witness, where there was only ever one
    // ------------------------------------------------------------------

    /**
     * A Bitcoin address, read from Blockstream's own node.
     *
     * The wallet tool asks blockchain.info and nothing else, so a
     * balance and a transaction count arrived with one witness and
     * could never be corroborated — on the one kind of claim where a
     * second opinion is free, because the chain is public and several
     * people run an index of it.
     *
     * Blockstream rather than mempool.space: both run Esplora and
     * answer with the same bytes, so pairing them would be two tools
     * reading one source and the app counting them as agreement. This
     * is a different operator with a different node.
     */
    suspend fun btcSecondOpinion(address: String): Result<String> = runCatching {
        val a = address.trim()
        if (!Regex("^(bc1|[13])[a-zA-HJ-NP-Z0-9]{25,62}$").matches(a)) {
            return@runCatching "Not a Bitcoin address: $a"
        }
        val o = getOrNull("https://blockstream.info/api/address/$a")
            ?: return@runCatching "Blockstream could not be reached for $a, " +
                "so nothing is claimed either way."
        val root = json.parseToJsonElement(o).jsonObject
        val chain = root["chain_stats"] as? JsonObject
        val pool = root["mempool_stats"] as? JsonObject
        val funded = chain.long("funded_txo_sum") ?: 0L
        val spent = chain.long("spent_txo_sum") ?: 0L
        val txs = chain.int("tx_count") ?: 0
        if (txs == 0 && (pool.int("tx_count") ?: 0) == 0) {
            return@runCatching "Blockstream: address $a has never been used " +
                "(no transactions on chain)."
        }
        val sat = 100_000_000.0
        buildString {
            append("Bitcoin address — $a (Blockstream)\n")
            append("Balance: %.8f BTC\n".format(Locale.US, (funded - spent) / sat))
            append("Total received: %.8f BTC\n".format(Locale.US, funded / sat))
            append("Total sent: %.8f BTC\n".format(Locale.US, spent / sat))
            append("Transactions: $txs\n")
            (pool.int("tx_count") ?: 0).takeIf { it > 0 }?.let {
                append("Unconfirmed in the mempool: $it\n")
            }
            append("\nA second index of the same public chain, run by a ")
            append("different operator. Figures that disagree with the other ")
            append("explorer mean one of them is behind, not that the chain is.")
            append(DECLARED)
        }
    }

    /**
     * An aircraft registration, read from a second registry.
     *
     * The aircraft tool asks hexdb and nothing else for who owns a
     * tail number — a claim about a person or a company, from one
     * source, with no way to corroborate it. adsbdb is keyless, is
     * built from different underlying registries, and answers by
     * registration as well as by Mode-S.
     */
    suspend fun aircraftSecondOpinion(input: String): Result<String> = runCatching {
        val t = input.trim().uppercase().replace(" ", "")
        if (t.length < 2 || t.length > 12) {
            return@runCatching "Give a registration or a Mode-S hex code."
        }
        val o = getOrNull("https://api.adsbdb.com/v0/aircraft/${enc(t)}")
            ?: return@runCatching "adsbdb has no record of $t, or could not be " +
                "reached — this says nothing either way."
        val a = ((json.parseToJsonElement(o).jsonObject["response"] as? JsonObject)
            ?.get("aircraft") as? JsonObject)
            ?: return@runCatching "adsbdb has no aircraft record for $t."
        buildString {
            append("Aircraft — $t (adsbdb)\n")
            a.str("registration")?.let { append("Registration: $it\n") }
            a.str("mode_s")?.let { append("Mode-S: $it\n") }
            val made = listOfNotNull(a.str("manufacturer"), a.str("type"))
                .joinToString(" ")
            if (made.isNotBlank()) append("Type: $made")
            a.str("icao_type")?.let { append(" ($it)") }
            if (made.isNotBlank()) append("\n")
            a.str("registered_owner")?.let { append("Registered owner: $it\n") }
            a.str("registered_owner_country_name")?.let { append("Country: $it\n") }
            a.str("registered_owner_operator_flag_code")
                ?.let { append("Operator code: $it\n") }
            a.str("url_photo")?.let { append("Photo: $it\n") }
            append("\nA second registry, independent of the first. An owner ")
            append("the two disagree about is worth more attention than one ")
            append("they agree on.")
            append(DECLARED)
        }
    }

}
