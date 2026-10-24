package com.osint.unified.domain.executor

import com.osint.unified.domain.orStop
import com.osint.unified.domain.PersonName
import com.osint.unified.domain.andMore
import com.osint.unified.network.Http
import com.osint.unified.network.Http.bodyText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Sources that answer about a person.
 *
 * A name used to route to fourteen tools, of which four returned a
 * list of places to look, one guessed email addresses, one geocoded
 * the name as if it were a town, and two were company registries. So
 * searching for a person produced a page of links and very little
 * anybody had actually looked up — which is the difference between a
 * tool and a bookmark bar.
 *
 * Everything here fetches and parses. Every endpoint was probed live
 * from CI before being wired in, none takes a key, and none requires
 * an account. They answer different questions on purpose: who
 * publishes under this name, who writes code under it, who has been
 * sued under it, who posts under it, and what it is attached to.
 *
 * None of them is identification. A name is the weakest identifier
 * there is, and everything below says so in its own output.
 */
class PeopleApis(
    private val client: OkHttpClient = Http.client
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------
    // Research and publishing
    // ------------------------------------------------------------------

    /**
     * ORCID: the register researchers keep of themselves.
     *
     * The strongest keyless people source there is, where it applies.
     * An ORCID record is maintained by its owner and carries the
     * institutions they have been at, the names they have published
     * under, and often a verified email — which is a real identifier,
     * not a guess.
     */
    fun orcid(name: String): Result<String> = runCatching {
        val p = PersonName.parse(name)
        if (p.isOrganisation) {
            return@runCatching "ORCID holds researchers, not organisations, " +
                "so \"$name\" was not looked up there."
        }
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val rows = json.parseToJsonElement(
            get("https://pub.orcid.org/v3.0/expanded-search/?q=$q&rows=20")
        ).jsonObjectOrNull()?.get("expanded-result") as? JsonArray

        if (rows.isNullOrEmpty()) {
            return@runCatching "No ORCID record for \"$name\"."
        }
        buildString {
            append("ORCID — ${rows.size} researcher records matching \"$name\"")
            if (rows.size >= 20) append(" (the first 20)")
            append(":\n")
            rows.mapNotNull { it as? JsonObject }.take(20).forEach { o ->
                val id = o.str("orcid-id") ?: return@forEach
                val given = o.str("given-names").orEmpty()
                val family = o.str("family-names").orEmpty()
                val credit = o.str("credit-name")
                append("\n▸ ${listOf(given, family).filter { it.isNotBlank() }.joinToString(" ")}")
                if (!credit.isNullOrBlank() && credit != "$given $family") {
                    append("  (publishes as $credit)")
                }
                append("\n  https://orcid.org/$id\n")
                o.strings("other-name").takeIf { it.isNotEmpty() }?.let {
                    append("  Also known as: ${it.take(6).joinToString(", ")}\n")
                }
                o.strings("institution-name").takeIf { it.isNotEmpty() }?.let {
                    append("  Institutions: ${it.take(8).joinToString("; ")}\n")
                }
                // Published only when the researcher chose to make it
                // public, which is what makes it worth having.
                o.strings("email").takeIf { it.isNotEmpty() }?.let {
                    append("  Public email: ${it.joinToString(", ")}\n")
                }
            }
            andMore(rows.size, 20)
            append("\nAn ORCID record is maintained by the person it describes. ")
            append("Matching names are not the same person until something else agrees.")
        }
    }

    /**
     * Crossref: what has been published under this name, and with whom.
     *
     * Co-authorship is the pivot here. A list of papers is a list of
     * dates and titles; the people beside them on the byline are a
     * network, and that is the part an investigator cannot get from a
     * search engine.
     */
    fun publications(name: String): Result<String> = runCatching {
        val p = PersonName.parse(name)
        if (p.isOrganisation) {
            return@runCatching "No author search was run: \"$name\" reads as an " +
                "organisation rather than a person."
        }
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val body = get(
            "https://api.crossref.org/works?query.author=$q&rows=25&sort=published" +
                "&order=desc&select=title,author,issued,DOI,container-title,type"
        )
        // Null and empty are different answers.
        //
        // `jsonObjectOrNull()` is `as? JsonObject`, so it returns
        // null for ANY body that is not an object — and Crossref
        // answers 200 with a `message` that is an array for a
        // validation failure, while a proxy or an interstitial
        // answers with HTML. All of those were arriving as "No
        // published work by X in Crossref", which Findings reads as
        // an established absence about a person.
        val root = json.parseToJsonElement(body).jsonObjectOrNull()
            ?: throw java.io.IOException("Crossref answered in an unexpected shape")
        val message = root["message"] as? JsonObject
            ?: throw java.io.IOException(
                "Crossref did not return a result set" +
                    ((root["message"] as? JsonPrimitive)?.contentOrNull
                        ?.let { ": $it" } ?: "")
            )
        val items = message["items"] as? JsonArray
            ?: throw java.io.IOException("Crossref returned no items block")
        if (items.isEmpty()) {
            return@runCatching "No published work by \"$name\" in Crossref."
        }

        // Crossref's author query is fuzzy, so a surname match on a
        // common name returns the whole field. Only works carrying an
        // author whose family name actually matches are kept, and the
        // count below is of those rather than of the query's total.
        val family = p.family.lowercase().takeIf { it.isNotBlank() }
        val kept = items.mapNotNull { it as? JsonObject }.filter { w ->
            family == null || (w["author"] as? JsonArray).orEmpty().any { a ->
                (a as? JsonObject).str("family")?.lowercase() == family
            }
        }
        if (kept.isEmpty()) {
            return@runCatching "No published work by \"$name\" in Crossref " +
                "(${items.size} works matched the words of the name, none with " +
                "an author surnamed ${p.family})."
        }

        val coauthors = LinkedHashMap<String, Int>()
        buildString {
            append("Crossref — ${kept.size} works with an author surnamed ")
            append("${p.family}, newest first:\n")
            kept.take(15).forEach { w ->
                val title = (w["title"] as? JsonArray)?.firstOrNull()
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim()
                val year = ((w["issued"] as? JsonObject)?.get("date-parts") as? JsonArray)
                    ?.firstOrNull()?.let { it as? JsonArray }
                    ?.firstOrNull()?.let { (it as? JsonPrimitive)?.intOrNull }
                append("\n• ${year ?: "undated"}  ${title ?: "(untitled)"}")
                (w["container-title"] as? JsonArray)?.firstOrNull()
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotBlank() }?.let { append("\n  $it") }
                w.str("DOI")?.let { append("\n  https://doi.org/$it") }
                append("\n")
                (w["author"] as? JsonArray).orEmpty().forEach { a ->
                    val o = a as? JsonObject ?: return@forEach
                    val who = listOfNotNull(o.str("given"), o.str("family"))
                        .joinToString(" ").trim()
                    if (who.isNotBlank() && o.str("family")?.lowercase() != family) {
                        coauthors[who] = (coauthors[who] ?: 0) + 1
                    }
                }
            }
            andMore(kept.size, 15)
            if (coauthors.isNotEmpty()) {
                append("\nCo-authors, most frequent first — these are the people ")
                append("this name works with:\n")
                coauthors.entries.sortedByDescending { it.value }.take(20)
                    .forEach { append("• ${it.key} (${it.value})\n") }
                andMore(coauthors.size, 20)
            }
        }
    }

    // ------------------------------------------------------------------
    // Accounts under a real name
    // ------------------------------------------------------------------

    /**
     * GitHub, searched by the name on the profile rather than the handle.
     *
     * The username sweeps ask "is this handle taken". This asks the
     * opposite and much more useful question: who has put this name
     * on an account. The profile then usually carries a company, a
     * location and a personal site.
     */
    suspend fun codeAccounts(name: String): Result<String> = runCatching {
        val n = name.trim()
        if (n.length < 3) return@runCatching "Give a full name to search GitHub profiles."
        val q = URLEncoder.encode("\"$n\" in:fullname", "UTF-8")
        val found = json.parseToJsonElement(
            get("https://api.github.com/search/users?q=$q&per_page=10")
        ).jsonObjectOrNull()
        val items = found?.get("items") as? JsonArray
        val total = (found?.get("total_count") as? JsonPrimitive)?.intOrNull ?: 0
        if (items.isNullOrEmpty()) {
            return@runCatching "No GitHub account carries the name \"$n\"."
        }

        // The search result holds only the handle, so each profile is
        // read once — bounded at five, because this is a free API with
        // sixty requests an hour and a dossier makes several.
        val logins = items.mapNotNull { (it as? JsonObject).str("login") }.take(5)
        val profiles = coroutineScope {
            logins.map { login ->
                async {
                    login to runCatching {
                        json.parseToJsonElement(
                            get("https://api.github.com/users/$login")
                        ).jsonObjectOrNull()
                    }.getOrNull()
                }
            }.map { it.await() }
        }

        buildString {
            append("GitHub — $total account")
            if (total != 1) append("s")
            append(" with \"$n\" as the profile name")
            if (total > 5) append("; the first 5 read in full")
            append(":\n")
            profiles.forEach { (login, o) ->
                append("\n▸ $login — https://github.com/$login\n")
                if (o == null) {
                    append("  (the profile itself could not be read)\n")
                    return@forEach
                }
                o.str("name")?.takeIf { it.isNotBlank() }?.let { append("  Name: $it\n") }
                o.str("company")?.takeIf { it.isNotBlank() }?.let { append("  Company: $it\n") }
                o.str("location")?.takeIf { it.isNotBlank() }?.let { append("  Location: $it\n") }
                o.str("blog")?.takeIf { it.isNotBlank() }?.let { append("  Site: $it\n") }
                o.str("email")?.takeIf { it.isNotBlank() }?.let { append("  Public email: $it\n") }
                o.str("created_at")?.let { append("  Joined: ${it.substringBefore('T')}\n") }
            }
            append("\nA profile name is whatever its owner typed.")
        }
    }.orStop()

    /**
     * Bluesky, searched by display name.
     *
     * Open, keyless, and the handle is frequently a domain the person
     * controls — which is a pivot straight back into the rest of this
     * app.
     */
    fun socialByName(name: String): Result<String> = runCatching {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val actors = json.parseToJsonElement(
            get("https://public.api.bsky.app/xrpc/app.bsky.actor.searchActors?q=$q&limit=25")
        ).jsonObjectOrNull()?.get("actors") as? JsonArray
        if (actors.isNullOrEmpty()) {
            return@runCatching "No Bluesky account found under the name \"$name\"."
        }
        buildString {
            append("Bluesky — ${actors.size} accounts matching \"$name\":\n")
            actors.mapNotNull { it as? JsonObject }.forEach { o ->
                val handle = o.str("handle") ?: return@forEach
                append("\n▸ ${o.str("displayName")?.takeIf { it.isNotBlank() } ?: handle}\n")
                append("  @$handle — https://bsky.app/profile/$handle\n")
                // A handle that is a domain is a domain this app can
                // investigate, which is the whole reason this source
                // is worth having.
                if (!handle.endsWith(".bsky.social") && handle.contains('.')) {
                    append("  Handle is a verified domain: $handle\n")
                }
                o.str("description")?.takeIf { it.isNotBlank() }
                    ?.let { append("  ${it.replace('\n', ' ').take(160)}\n") }
            }
        }
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    /**
     * US federal court records, by name.
     *
     * CourtListener's RECAP archive is the public docket, and a docket
     * is the kind of record a search engine will not hand you: who
     * sued whom, in which court, on what date, with the case number.
     * Free Law Project publishes it keylessly and this is a plain
     * search against it.
     */
    fun courtRecords(name: String): Result<String> = runCatching {
        val n = name.trim()
        // Quoted, so the search is for the phrase rather than for
        // every document containing either word.
        val q = URLEncoder.encode("\"$n\"", "UTF-8")
        val o = json.parseToJsonElement(
            get("https://www.courtlistener.com/api/rest/v4/search/?q=$q&type=r&order_by=dateFiled%20desc")
        ).jsonObjectOrNull()
        val count = (o?.get("count") as? JsonPrimitive)?.intOrNull ?: 0
        val results = o?.get("results") as? JsonArray
        if (count == 0 || results.isNullOrEmpty()) {
            return@runCatching "No US federal court docket names \"$n\" " +
                "(CourtListener RECAP)."
        }
        buildString {
            append("US court dockets — $count naming \"$n\"")
            append(", most recent first:\n")
            results.mapNotNull { it as? JsonObject }.take(20).forEach { r ->
                append("\n• ${r.str("caseName") ?: "(unnamed case)"}")
                r.str("court")?.let { append("\n  $it") }
                r.str("docketNumber")?.let { append("  ·  $it") }
                r.str("dateFiled")?.let { append("\n  Filed $it") }
                r.str("assignedTo")?.takeIf { it.isNotBlank() }
                    ?.let { append("\n  Judge: $it") }
                r.str("absolute_url")?.let {
                    append("\n  https://www.courtlistener.com$it")
                }
                append("\n")
            }
            andMore(results.size, 20)
            if (count > 20) append("\n…and ${count - 20} more.\n")
            append("\nRECAP holds what somebody has paid to pull out of PACER, ")
            append("so it is a large sample of the federal docket and not all of it. ")
            append("A name in a case caption is a party, a lawyer or a judge.")
        }
    }

    /**
     * US non-profits, by name.
     *
     * Reaches two things at once: an organisation someone runs, and
     * the foundations named after a person. Every result carries an
     * EIN, which is an exact identifier and a pivot into the filings.
     */
    fun nonprofits(name: String): Result<String> = runCatching {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val o = json.parseToJsonElement(
            get("https://projects.propublica.org/nonprofits/api/v2/search.json?q=$q")
        ).jsonObjectOrNull()
        val orgs = o?.get("organizations") as? JsonArray
        val total = (o?.get("total_results") as? JsonPrimitive)?.intOrNull ?: 0
        if (orgs.isNullOrEmpty()) {
            return@runCatching "No US registered non-profit matches \"$name\"."
        }
        buildString {
            append("US non-profits — $total matching \"$name\"")
            if (total > orgs.size) append(", the first ${orgs.size}")
            append(":\n")
            orgs.mapNotNull { it as? JsonObject }.take(20).forEach { org ->
                append("\n• ${org.str("name")}")
                val where = listOfNotNull(
                    org.str("city")?.takeIf { it.isNotBlank() },
                    org.str("state")?.takeIf { it.isNotBlank() }
                ).joinToString(", ")
                if (where.isNotBlank()) append("\n  $where")
                org.str("strein")?.let { append("\n  EIN $it") }
                (org["ein"] as? JsonPrimitive)?.intOrNull?.let {
                    append("\n  https://projects.propublica.org/nonprofits/organizations/$it")
                }
                append("\n")
            }
            andMore(orgs.size, 20)
            append("\nThe filings behind each of these name their officers and ")
            append("their pay, which is the part worth reading.")
        }
    }

    /**
     * Published books, by author.
     *
     * Open Library is keyless and its author records carry birth and
     * death years, alternate names and the span of a career — dates
     * attached to a name, which is what a timeline is made of.
     */
    fun authorWorks(name: String): Result<String> = runCatching {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        // As with Crossref above: a body that is not an object, or
        // one with no `docs` block, is a refusal and not an empty
        // shelf.
        val o = json.parseToJsonElement(
            get("https://openlibrary.org/search/authors.json?q=$q")
        ).jsonObjectOrNull()
            ?: throw java.io.IOException("Open Library answered in an unexpected shape")
        val docs = o["docs"] as? JsonArray
            ?: throw java.io.IOException("Open Library returned no docs block")
        if (docs.isEmpty()) {
            return@runCatching "No published author named \"$name\" in Open Library."
        }
        buildString {
            append("Open Library — ${docs.size} authors matching \"$name\":\n")
            docs.mapNotNull { it as? JsonObject }.take(12).forEach { d ->
                append("\n▸ ${d.str("name")}")
                val born = d.str("birth_date")
                val died = d.str("death_date")
                if (born != null || died != null) {
                    append("  (${born ?: "?"} – ${died ?: ""})")
                }
                append("\n")
                (d["work_count"] as? JsonPrimitive)?.intOrNull
                    ?.let { append("  $it works\n") }
                d.str("top_work")?.let { append("  Best known for: $it\n") }
                d.strings("alternate_names").takeIf { it.isNotEmpty() }?.let {
                    append("  Also published as: ${it.take(6).joinToString("; ")}\n")
                }
                d.str("key")?.let { append("  https://openlibrary.org/authors/$it\n") }
            }
            andMore(docs.size, 12)
        }
    }

    // ------------------------------------------------------------------

    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject?.strings(k: String): List<String> =
        (this?.get(k) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            .orEmpty()

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.defaultUserAgent)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.bodyText().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }
}
