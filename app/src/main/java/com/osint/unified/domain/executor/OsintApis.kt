package com.osint.unified.domain.executor

import com.osint.unified.domain.Hostnames
import com.osint.unified.domain.andMore
import com.osint.unified.domain.orStop
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import com.osint.unified.network.Http
import com.osint.unified.network.Http.bodyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Keyless public OSINT data sources the app queries directly (no backend, no
 * API keys): certificate transparency, DNS-over-HTTPS, IP geolocation/ASN,
 * Gravatar, the Wayback Machine, and reverse geocoding.
 */
class OsintApis(private val client: OkHttpClient = Http.client) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Ceilings for the certificate-transparency walk, which is the
     *  one lookup in here with no natural end. */
    private val CT_PAGE = 1000
    private val MAX_CT_PAGES = 5
    private val MAX_CT_NAMES = 5000
    /**
     * What this app calls itself, which is deliberately not itself.
     *
     * The network layer sets a plain mobile-browser agent on every
     * request for a reason it states at length: several of these calls go to
     * hosts the TARGET controls — reading their security.txt,
     * fetching their certificate, crawling their page — and a user
     * agent announcing an OSINT tool writes "somebody is
     * investigating you" into their access log. This class overrode
     * that for every request it made, which is the policy exactly
     * inverted.
     *
     * [RESEARCH_UA] remains for the services whose terms ask to be
     * told who is calling; SEC EDGAR builds its own, from the
     * investigator's address, in SecAgent.
     */
    private val ua = com.osint.unified.network.Http.defaultUserAgent

    /**
     * For a service whose terms ask to be told who is calling.
     *
     * OpenStreetMap's Nominatim and Overpass both require an agent
     * that identifies the application, so for those the honest
     * string is also the compliant one — and none of them is a host
     * the target controls, which is the only reason the browser
     * string exists.
     */
    private val researchUa = "OSINT-Unified/1.0 " +
        "(+https://github.com/EmotionalLeaves/osint-unified)"

    /** Hosts that ask to be told, by their own usage policy. */
    private fun agentFor(url: String, fallback: String): String {
        val host = runCatching {
            url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        }.getOrDefault("")
        val asks = host.endsWith("openstreetmap.org") ||
            host.endsWith("overpass-api.de") ||
            host.endsWith("openstreetmap.fr") ||
            host.endsWith("kartaview.org") ||
            host.endsWith("panoramax.xyz") ||
            host.endsWith("openstreetcam.org")
        return if (asks) researchUa else fallback
    }

    /**
     * Certificate-transparency subdomain discovery.
     *
     * crt.sh is the fullest index and also the slowest — it runs the query
     * against a Postgres replica and regularly takes well over the shared
     * client's call timeout, which showed up as "no certificates found" when
     * the truth was "did not finish". So it gets the patient client, and Cert
     * Spotter — a separate CT index, fast and keyless — answers when crt.sh
     * will not, instead of the lookup coming back empty.
     */
    fun crtsh(domain: String): Result<String> = runCatching {
        val d = domain.trim().removePrefix("*.").lowercase()
        val subs = LinkedHashSet<String>()

        fun record(raw: String) {
            raw.split("\n").forEach { line ->
                val v = line.trim().removePrefix("*.").lowercase()
                // The domain itself or something genuinely under it.
                //
                // The same bare endsWith that made "notexample.com" a
                // subdomain of "ample.com" in the other sweep, and it
                // bites harder here: Cert Spotter returns every name
                // on each certificate, so one multi-tenant cert hands
                // this a list of other people's domains to attribute
                // to the target. crt.sh is graded A, so they went
                // into the corroboration pass at full weight.
                if (v == d || v.endsWith(".$d")) subs.add(v)
            }
        }

        // crt.sh answers a wildcard query for a large domain with
        // tens of thousands of rows, and this walked all of them
        // into a set that is then sorted and printed. Bounded, and
        // honest about the bound.
        var crtTruncated = false
        val crtFailure = runCatching {
            val url = "https://crt.sh/?q=" + URLEncoder.encode("%.$d", "UTF-8") + "&output=json"
            val rows = json.parseToJsonElement(getBody(url, patient = true)).jsonArray
            for (el in rows) {
                if (subs.size >= MAX_CT_NAMES) { crtTruncated = true; break }
                ((el as? JsonObject)?.get("name_value") as? JsonPrimitive)?.contentOrNull
                    ?.let(::record)
            }
        }.exceptionOrNull()
        val fromCrtSh = subs.size

        // Cert Spotter pages, and one request is one page.
        //
        // The call asked once and reported whatever came back as the
        // complete set. A domain with more issuances than a page
        // holds — which is most domains worth looking at — got a
        // silent prefix presented as an answer, and "N names" read
        // as a count rather than as the start of one. It follows the
        // id cursor now, and says so when it stops early.
        var spotterPages = 0
        var spotterMore = false
        val spotterFailure = runCatching {
            var after: String? = null
            while (spotterPages < MAX_CT_PAGES) {
                val url = "https://api.certspotter.com/v1/issuances?domain=$d" +
                    "&include_subdomains=true&expand=dns_names&limit=$CT_PAGE" +
                    (after?.let { "&after=" + URLEncoder.encode(it, "UTF-8") } ?: "")
                val page = json.parseToJsonElement(getBody(url)).jsonArray
                spotterPages++
                page.forEach { el ->
                    ((el as? JsonObject)?.get("dns_names") as? JsonArray)?.forEach { n ->
                        (n as? JsonPrimitive)?.contentOrNull?.let(::record)
                    }
                }
                if (page.size < CT_PAGE) break
                after = ((page.last() as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                if (after == null) break
                if (spotterPages >= MAX_CT_PAGES) spotterMore = true
            }
        }.exceptionOrNull()

        when {
            subs.isNotEmpty() -> buildString {
                append("Certificate transparency — ${subs.size} names for $d")
                append(" (crt.sh $fromCrtSh")
                append(", Cert Spotter +${subs.size - fromCrtSh}")
                append(")")
                if (crtTruncated) {
                    append("\ncrt.sh returned more than $MAX_CT_NAMES names and was cut ")
                    append("off there, so this list is a prefix, not the whole index.")
                }
                if (spotterMore) {
                    append("\nCert Spotter had further pages after ${MAX_CT_PAGES * CT_PAGE} ")
                    append("issuances; they were not fetched.")
                }
                if (crtFailure != null) append("\ncrt.sh did not answer: ${crtFailure.message}")
                append("\n")
                append(subs.sorted().joinToString("\n"))
            }
            crtFailure != null && spotterFailure != null ->
                "Certificate transparency unreachable — crt.sh: ${crtFailure.message}; " +
                    "Cert Spotter: ${spotterFailure.message}"
            // Only a clean sheet when the index actually answered.
            //
            // crt.sh failing while Cert Spotter happens to return
            // nothing used to land here, and "no certificates found"
            // is a statement about the domain made from a source
            // that was never read.
            crtFailure != null ->
                "Cert Spotter has no certificates for $d, and crt.sh did not " +
                    "answer (${crtFailure.message}) — so this is half an " +
                    "answer, not a clean sheet."
            spotterFailure != null ->
                "crt.sh has no certificates for $d, and Cert Spotter did not " +
                    "answer (${spotterFailure.message}) — so this is half an " +
                    "answer, not a clean sheet."
            else -> "Certificate transparency: no certificates found for $d."
        }
    }

    /**
     * A DNS lookup, or null when the resolver could not be reached.
     *
     * Null and empty are different answers and this used to return
     * the same thing for both. Everything built on it then read a
     * failed lookup as "there is no such record": a domain with full
     * SPF and DMARC was reported as "Spoofing risk: HIGH (no SPF or
     * DMARC)" when DoH was blocked, every blocklist came back clean
     * when none of them had answered, and a hundred and sixty
     * lookalike domains were reported as "generated and resolved"
     * having resolved none.
     */
    private fun dohOrNull(name: String, type: String): List<String>? = runCatching {
        val o = json.parseToJsonElement(
            "https://dns.google/resolve?name=${URLEncoder.encode(name, "UTF-8")}&type=$type"
                .let { getBody(it) }
        ).jsonObject
        // The resolver's own status, which was not being read.
        //
        // dns.google answers HTTP 200 for SERVFAIL (2) and REFUSED
        // (5) with no Answer array — exactly what a blocklist returns
        // to a public resolver it is rate limiting. Treated as an
        // empty answer, that is "not listed" from a question nobody
        // answered. 0 is NOERROR and 3 is NXDOMAIN, and NXDOMAIN
        // genuinely is "no such name", which is an answer.
        val status = (o["Status"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (status != null && status != 0 && status != 3) {
            throw IOException("resolver status $status")
        }
        o["Answer"]?.jsonArray?.mapNotNull {
            ((it as? JsonObject)?.get("data") as? JsonPrimitive)?.contentOrNull?.trim('"')
        } ?: emptyList()
    }.getOrNull()

    /** The same, for callers that genuinely do not care which it was. */
    private fun dohTxt(name: String, type: String): List<String> =
        dohOrNull(name, type) ?: emptyList()

    /** Email-domain security posture: MX, SPF and DMARC via DoH (keyless). */
    fun mailSecurity(input: String): Result<String> = runCatching {
        val domain = input.substringAfter('@').trim().lowercase()
        val mxAnswer = dohOrNull(domain, "MX")
        val txtAnswer = dohOrNull(domain, "TXT")
        val dmarcAnswer = dohOrNull("_dmarc.$domain", "TXT")
        // A resolver that did not answer is not a domain with no
        // records. Reported as HIGH risk, this was the app telling
        // someone that a correctly configured domain can be spoofed.
        if (mxAnswer == null || txtAnswer == null || dmarcAnswer == null) {
            return@runCatching "Mail security could not be reached for $domain: " +
                "the DNS resolver did not answer, so nothing is known about " +
                "its SPF, DMARC or MX records."
        }
        val mx = mxAnswer
        val spf = txtAnswer.filter { it.startsWith("v=spf1", true) }
        val dmarc = dmarcAnswer.filter { it.startsWith("v=DMARC1", true) }
        buildString {
            append("Domain: $domain\n")
            append("MX: ${mx.joinToString(", ").ifBlank { "(none — domain may not receive mail)" }}\n")
            append("SPF: ${spf.firstOrNull() ?: "(missing)"}\n")
            append("DMARC: ${dmarc.firstOrNull() ?: "(missing)"}\n")
            // Anchored to a tag boundary.
            //
            // A bare `p=` matched inside `sp=`, which is the policy
            // for SUBDOMAINS. "v=DMARC1; sp=reject; p=none" was read
            // as p=reject and the domain reported LOW risk when its
            // actual policy is none — the one answer this tool exists
            // to get right, inverted.
            val policy = Regex("(?:^|;)\\s*p\\s*=\\s*(\\w+)", RegexOption.IGNORE_CASE)
                .find(dmarc.firstOrNull().orEmpty())?.groupValues?.get(1)?.lowercase()
            append("Spoofing risk: ${when {
                spf.isEmpty() && dmarc.isEmpty() -> "HIGH (no SPF or DMARC)"
                dmarc.isEmpty() || policy == "none" -> "MEDIUM (no enforced DMARC)"
                else -> "LOW (DMARC p=$policy)"
            }}")
        }
    }

    /** HTTP security headers + server banner for a domain/URL. */
    fun securityHeaders(input: String): Result<String> = runCatching {
        val url = withScheme(input)
        val request = Request.Builder().url(url).header("User-Agent", ua).head().build()
        client.newCall(request).execute().use { resp ->
            val checks = listOf(
                "Strict-Transport-Security", "Content-Security-Policy", "X-Frame-Options",
                "X-Content-Type-Options", "Referrer-Policy", "Permissions-Policy"
            )
            // A server that refuses HEAD has not told us it ships no
            // headers. 405, 403 and 501 to HEAD are all common, and
            // the answer was "Security headers: 0/6" with six crosses
            // under it — a confident statement that the site has no
            // HSTS, no CSP and no X-Frame-Options.
            if (!resp.isSuccessful && resp.code !in 300..399) {
                return@runCatching "The server answered HTTP ${resp.code} to a HEAD " +
                    "request, so its security headers could not be read."
            }
            buildString {
                append("HTTP ${resp.code} — ${resp.request.url}\n")
                resp.header("Server")?.let { append("Server: $it\n") }
                resp.header("X-Powered-By")?.let { append("Powered-By: $it\n") }
                val present = checks.count { resp.header(it) != null }
                append("Security headers: $present/${checks.size}\n")
                checks.forEach { h -> append("${if (resp.header(h) != null) "✓" else "✗"} $h\n") }
            }
        }
    }

    /** Reverse DNS (PTR) for an IPv4 via DoH. */
    fun reverseDns(ip: String): Result<String> = runCatching {
        val v = ip.trim()
        // A reverse lookup needs a single IPv4 address, and the router
        // classifies IPv6 and CIDR as IpAddress too. "8.8.8.0/24"
        // became the query "0/24.8.8.8.in-addr.arpa" and
        // "2001:4860::8888" became itself with ".in-addr.arpa" glued
        // on the end — malformed questions whose non-answer was then
        // reported as "No PTR record for …", an established absence
        // from a question nobody could have answered.
        if (!ipRegex.matches(v)) {
            return@runCatching "A reverse lookup needs one IPv4 address; " +
                "\"$v\" is not one, so no PTR query was made." +
                if (v.contains(':')) {
                    " IPv6 reverse records live under ip6.arpa, which this " +
                        "tool does not build yet."
                } else {
                    ""
                }
        }
        val arpa = v.split('.').reversed().joinToString(".") + ".in-addr.arpa"
        // Null and empty are different answers, and this used the
        // helper that conflates them — so a blocked resolver was
        // reported as the address having no reverse record.
        val ptr = dohOrNull(arpa, "PTR")
        when {
            ptr == null ->
                "The DNS resolver could not be reached for $v, so nothing " +
                    "is known about its reverse record."
            ptr.isEmpty() -> "No PTR record for $v."
            else -> "PTR: ${ptr.joinToString(", ")}"
        }
    }

    /** For reading a handshake: the first response is the one that has it. */
    private val noRedirects: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    private fun hostOf(input: String) =
        input.removePrefix("https://").removePrefix("http://").substringBefore('/').trim()

    /** TLS certificate: issuer, validity window, days left, and every hostname
     *  the cert covers (SANs) — read straight from the handshake. */
    fun tlsCert(input: String): Result<String> = runCatching {
        val host = hostOf(input)
        val request = Request.Builder().url("https://$host").head().header("User-Agent", ua).build()
        // Not following redirects: the certificate is the first answer's.
        //
        // The client followed them, and a site that sent HTTPS visitors
        // to plain http:// took the request somewhere Android's network
        // policy refuses to go — so the tool died with "CLEARTEXT
        // communication not permitted" after the handshake it was asked
        // for had already happened, and the dossier filed the host as
        // unreachable. The redirect is itself worth saying.
        noRedirects.newCall(request).execute().use { resp ->
            val hs = resp.handshake ?: return@runCatching "No TLS handshake with $host."
            val cert = hs.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                ?: return@runCatching "No certificate presented by $host."
            val days = (cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L
            buildString {
                append("Host: $host\n")
                if (resp.isRedirect) {
                    val to = resp.header("Location").orEmpty()
                    if (to.startsWith("http://", ignoreCase = true)) {
                        append("⚠ Redirects HTTPS visitors to plain HTTP: $to\n")
                    } else if (to.isNotBlank()) {
                        append("Redirects to: $to\n")
                    }
                }
                append("Issuer: ${cert.issuerX500Principal.name}\n")
                append("Subject: ${cert.subjectX500Principal.name}\n")
                append("Valid from: ${cert.notBefore}\n")
                append("Expires: ${cert.notAfter}\n")
                append("Days remaining: $days\n")
                append("TLS: ${hs.tlsVersion} / ${hs.cipherSuite}\n")
                val sans = runCatching {
                    cert.subjectAlternativeNames?.mapNotNull { (it as? List<*>)?.getOrNull(1) as? String }
                }.getOrNull().orEmpty()
                if (sans.isNotEmpty()) {
                    append("Covers ${sans.size} hostnames: ")
                    append(sans.take(40).joinToString(", "))
                    append("\n")
                    // A count followed by a shorter list is a reader
                    // concluding the certificate covers forty names.
                    andMore(sans.size, 40)
                }
            }
        }
    }

    /** robots.txt, security.txt and sitemap — the files a site publishes about
     *  itself, often naming paths it would rather not advertise. */
    // orStop on the OUTER runCatching, which is where a cancellation
    // actually arrives.
    //
    // It was on the inner one, around getBody — a plain blocking
    // function that cannot throw a CancellationException, so the
    // guard was a no-op. The cancellation surfaces at it.await()
    // below, inside this one.
    suspend fun siteFiles(input: String): Result<String> = runCatching {
        val host = hostOf(input)
        val out = StringBuilder()
        // A 404 is the site saying it does not publish that file; a
        // DNS failure, a timeout or a 500 is the site saying nothing.
        // All four fetches were wrapped in a bare runCatching, so on
        // a captive-portal Wi-Fi — or against a host that is simply
        // down — the dossier recorded that the site publishes no
        // security.txt, having never reached it.
        var answered = 0
        // Three requests to one host, asked together rather than in
        // turn. They are independent, and run serially they were three
        // round trips deep — on a phone, against a slow host, that is
        // most of this tool's time for no reason. The per-host gate in
        // the politeness interceptor still holds them to four at once,
        // so this is not three times the load on anyone.
        val paths = listOf("robots.txt", ".well-known/security.txt", "sitemap.xml")
        val fetched = coroutineScope {
            paths.map { path ->
                async { path to runCatching { getBody("https://$host/$path") }.orStop() }
            }.map { it.await() }
        }
        fetched.forEach { (path, attempt) ->
            val code = httpCodeOf(attempt.exceptionOrNull())
            // 404 and 410 are answers. Anything else that threw is not.
            if (attempt.isSuccess || code == 404 || code == 410) answered++
            val body = attempt.getOrNull() ?: return@forEach
            if (body.isNotBlank() && !body.take(200).contains("<html", true)) {
                out.append("--- /$path ---\n")
                out.append(body.lineSequence().filter { it.isNotBlank() }.take(25).joinToString("\n"))
                out.append("\n\n")
            }
        }
        when {
            out.isNotBlank() -> out.toString().trim()
            answered == 0 ->
                "No reply from $host for robots.txt, security.txt or sitemap, " +
                    "so nothing is known about what it publishes."
            else -> "No robots.txt, security.txt or sitemap found on $host."
        }
    }.orStop()

    /** Is this IP a Tor exit node? (official bulk exit list, keyless) */
    fun torExit(ip: String): Result<String> = runCatching {
        val target = ip.trim()
        // The bulk exit list is IPv4. Saying "is not a Tor exit node"
        // about an IPv6 address on the strength of searching an IPv4
        // list is a clean sheet from the wrong question.
        if (!ipRegex.matches(target)) {
            return@runCatching "The Tor bulk exit list is IPv4 only, so " +
                "nothing is claimed about \"$target\"."
        }
        val list = getBody("https://check.torproject.org/torbulkexitlist")
        val exits = list.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (target in exits) "⚠ $target IS a Tor exit node (anonymised traffic)."
        else "$target is not a Tor exit node (checked ${exits.size} known exits)."
    }

    /** DNS-over-HTTPS record lookup (Google DoH). */
    fun dns(domain: String): Result<String> = runCatching {
        val sb = StringBuilder()
        // Through the helper, which keeps "the resolver said no
        // records" apart from "the resolver did not answer". This
        // loop had its own copy that swallowed both into
        // emptyList(), so on a network where DoH is blocked — which
        // is most filtered corporate and some mobile ones — a domain
        // with full A, MX and NS came back "No DNS records found."
        var asked = 0
        listOf("A", "AAAA", "MX", "NS", "TXT").forEach { type ->
            val values = dohOrNull(domain, type) ?: return@forEach
            asked++
            if (values.isNotEmpty()) sb.append("$type: ${values.joinToString(", ")}\n")
        }
        when {
            asked == 0 ->
                "The DNS resolver could not be reached for $domain, so nothing " +
                    "is known about its records."
            sb.isBlank() -> "No DNS records found for $domain."
            else -> sb.toString()
        }
    }

    /** IP geolocation + ASN/ISP (ipwho.is). */
    fun ipinfo(ip: String): Result<String> = runCatching {
        val o = json.parseToJsonElement(getBody("https://ipwho.is/$ip")).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val conn = o["connection"] as? JsonObject
        fun c(k: String) = (conn?.get(k) as? JsonPrimitive)?.contentOrNull
        // ipwho.is answers HTTP 200 with success:false for a reserved,
        // bogon or malformed address, and this never read the flag —
        // so the output was "Location: null, null, null / ISP: null /
        // ASN: null", printed as facts and classified as a finding.
        val ok = (o["success"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        if (ok == false) {
            val why = (o["message"] as? JsonPrimitive)?.contentOrNull
            return@runCatching "No geolocation record for $ip" +
                (why?.let { " ($it)" } ?: "") + "."
        }
        buildString {
            append("IP: ${s("ip") ?: ip}\n")
            // Each line only where there is something to put on it. A
            // field the service left out is not a field whose value
            // is the word "null".
            val place = listOfNotNull(s("city"), s("region"), s("country"))
            if (place.isNotEmpty()) append("Location: ${place.joinToString(", ")}\n")
            val lat = s("latitude")
            val lon = s("longitude")
            if (lat != null && lon != null) append("Coordinates: $lat, $lon\n")
            c("isp")?.let { append("ISP: $it\n") }
            c("org")?.let { append("Org: $it\n") }
            c("asn")?.let { append("ASN: $it\n") }
        }.ifBlank { "No geolocation record for $ip." }
    }

    /** Gravatar profile lookup by email hash. */
    fun gravatar(email: String): Result<String> = runCatching {
        val hash = md5(email.trim().lowercase())
        val avatar = "https://www.gravatar.com/avatar/$hash"
        val request = Request.Builder().url("https://www.gravatar.com/$hash.json").header("User-Agent", ua).build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@runCatching "No Gravatar profile.\nDefault avatar: $avatar"
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val entry = json.parseToJsonElement(resp.body?.string().orEmpty())
                .jsonObject["entry"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@runCatching "Gravatar exists.\nAvatar: $avatar"
            fun s(k: String) = (entry[k] as? JsonPrimitive)?.contentOrNull
            buildString {
                append("Avatar: $avatar\n")
                s("displayName")?.let { append("Name: $it\n") }
                s("aboutMe")?.let { append("About: $it\n") }
                (entry["currentLocation"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { append("Location: $it\n") }
                (entry["accounts"] as? JsonArray)?.forEach { a ->
                    val ao = a.jsonObject
                    val url = (ao["url"] as? JsonPrimitive)?.contentOrNull
                    val name = (ao["shortname"] as? JsonPrimitive)?.contentOrNull ?: ""
                    if (url != null) append("Account: $name $url\n")
                }
                (entry["urls"] as? JsonArray)?.forEach { u ->
                    (u.jsonObject["value"] as? JsonPrimitive)?.contentOrNull?.let { append("Link: $it\n") }
                }
                s("profileUrl")?.let { append("Profile: $it\n") }
            }
        }
    }

    /** Wayback Machine snapshot history for a domain. */
    fun wayback(domain: String): Result<String> = runCatching {
        try {
            val url = "https://web.archive.org/cdx/search/cdx?url=" +
                URLEncoder.encode("$domain*", "UTF-8") + "&output=json&limit=20&collapse=urlkey&fl=timestamp,original"
            val rows = json.parseToJsonElement(getBody(url)).jsonArray.drop(1)
            if (rows.isEmpty()) waybackLatest(domain)
            else "Wayback — ${rows.size} snapshots:\n" + rows.joinToString("\n") { r ->
                val a = r.jsonArray
                val ts = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: ""
                val orig = (a.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: ""
                "https://web.archive.org/web/$ts/$orig"
            }
        } catch (e: Exception) {
            // CDX is often overloaded (503) — fall back to the availability API.
            waybackLatest(domain)
        }
    }

    /** The single most recent archived snapshot via the availability API. */
    private fun waybackLatest(input: String): String {
        val target = withScheme(input)
        val o = json.parseToJsonElement(
            getBody("https://archive.org/wayback/available?url=" + URLEncoder.encode(target, "UTF-8"))
        ).jsonObject
        val snap = (o["archived_snapshots"] as? JsonObject)?.get("closest") as? JsonObject
        val url = (snap?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: return "No Wayback snapshots found for $target."
        val ts = (snap["timestamp"] as? JsonPrimitive)?.contentOrNull
        return "Latest snapshot${ts?.let { " ($it)" } ?: ""}:\n${url.replace("http://", "https://")}"
    }

    /** Reverse geocoding (OpenStreetMap Nominatim) + MGRS military grid. */
    fun revgeo(input: String): Result<String> = runCatching {
        // One parser, and a range check this never had: it took
        // parts[0].toDouble() with no bounds, so "500, 900" was sent
        // to Nominatim as a place on Earth.
        val here = com.osint.unified.domain.Coordinates.parse(input)
        require(here != null) { "Expected 'lat, lon'." }
        val lat = here.lat
        val lon = here.lon
        val o = json.parseToJsonElement(
            getBody("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon")
        ).jsonObject
        val display = (o["display_name"] as? JsonPrimitive)?.contentOrNull ?: "(no address found)"
        val mgrs = com.osint.unified.domain.Mgrs.from(lat, lon)
        buildString {
            append("Address: $display\n")
            append("Lat/Lon: $lat, $lon\n")
            if (mgrs != null) append("MGRS: $mgrs\n")
            append("Map: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=14/$lat/$lon")
        }
    }

    /** Shodan InternetDB: open ports, hostnames, CPEs and CVEs for an IP
     *  (keyless). Resolves a domain to an IP first via DoH. */
    fun shodan(input: String): Result<String> = runCatching {
        val ip = if (ipRegex.matches(input)) input
            else resolveA(input) ?: throw IOException("Could not resolve $input to an IP")
        val request = Request.Builder().url("https://internetdb.shodan.io/$ip").header("User-Agent", ua).build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@runCatching "Shodan InternetDB: no data for $ip."
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val o = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            fun arr(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            buildString {
                append("IP: $ip\n")
                append("Open ports: ${arr("ports").joinToString(", ").ifBlank { "(none)" }}\n")
                arr("hostnames").takeIf { it.isNotEmpty() }?.let { append("Hostnames: ${it.joinToString(", ")}\n") }
                arr("vulns").takeIf { it.isNotEmpty() }?.let { append("Vulnerabilities: ${it.joinToString(", ")}\n") }
                arr("cpes").takeIf { it.isNotEmpty() }?.let { append("CPEs: ${it.joinToString(", ")}\n") }
                arr("tags").takeIf { it.isNotEmpty() }?.let { append("Tags: ${it.joinToString(", ")}\n") }
            }
        }
    }

    /** RDAP registration data (keyless): registrar, key dates, nameservers,
     *  org/country for a domain or IP. The modern WHOIS. */
    fun rdap(input: String): Result<String> = runCatching {
        val isIp = ipRegex.matches(input)
        val url = if (isIp) "https://rdap.org/ip/$input" else "https://rdap.org/domain/$input"
        val o = json.parseToJsonElement(getBody(url)).jsonObject
        buildString {
            (o["ldhName"] as? JsonPrimitive)?.contentOrNull?.let { append("Name: $it\n") }
            (o["handle"] as? JsonPrimitive)?.contentOrNull?.let { append("Handle: $it\n") }
            (o["country"] as? JsonPrimitive)?.contentOrNull?.let { append("Country: $it\n") }
            (o["startAddress"] as? JsonPrimitive)?.contentOrNull?.let { append("Range: $it") }
            (o["endAddress"] as? JsonPrimitive)?.contentOrNull?.let { append(" – $it\n") }
            (o["events"] as? JsonArray)?.forEach { e ->
                val eo = e.jsonObject
                val a = (eo["eventAction"] as? JsonPrimitive)?.contentOrNull
                val d = (eo["eventDate"] as? JsonPrimitive)?.contentOrNull
                if (a != null && d != null) append("${a.replaceFirstChar { it.uppercase() }}: ${d.take(10)}\n")
            }
            (o["entities"] as? JsonArray)?.forEach { ent ->
                val eo = ent.jsonObject
                val role = (eo["roles"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                val vcard = (eo["vcardArray"] as? JsonArray)?.getOrNull(1) as? JsonArray
                val fn = vcard?.firstNotNullOfOrNull { row ->
                    val r = row as? JsonArray
                    if ((r?.getOrNull(0) as? JsonPrimitive)?.contentOrNull == "fn")
                        (r.getOrNull(3) as? JsonPrimitive)?.contentOrNull else null
                }
                if (fn != null) append("${role?.replaceFirstChar { it.uppercase() } ?: "Entity"}: $fn\n")
            }
            val ns = (o["nameservers"] as? JsonArray)?.mapNotNull {
                ((it as? JsonObject)?.get("ldhName") as? JsonPrimitive)?.contentOrNull
            }
            if (!ns.isNullOrEmpty()) append("Nameservers: ${ns.joinToString(", ")}\n")
            if (isBlank()) append("RDAP: no structured record for $input.")
        }
    }

    /**
     * BGP / ASN / prefix / RIR for an IP.
     *
     * BGPView is the nicer shape but goes down for long stretches, and when
     * it does this returned nothing at all for every IP. RIPEstat carries
     * the same routing facts and is run by an RIR, so it answers instead.
     */
    // Same again: the cancellation arrives at the await inside
    // ripeRouting, and this is the runCatching that catches it. It was
    // bare, so pressing STOP during an IP dossier wrote a SUCCESSFUL
    // row asserting RIPEstat had been consulted and had failed.
    suspend fun bgpview(ip: String): Result<String> = runCatching {
        val o = runCatching {
            json.parseToJsonElement(getBody("https://api.bgpview.io/ip/$ip"))
                .jsonObject["data"]?.jsonObject
        }.getOrNull() ?: return@runCatching ripeRouting(ip)
        buildString {
            (o["ip"] as? JsonPrimitive)?.contentOrNull?.let { append("IP: $it\n") }
            (o["ptr_record"] as? JsonPrimitive)?.contentOrNull?.let { append("PTR: $it\n") }
            (o["prefixes"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { p ->
                (p["prefix"] as? JsonPrimitive)?.contentOrNull?.let { append("Prefix: $it\n") }
                (p["asn"] as? JsonObject)?.let { asn ->
                    val num = (asn["asn"] as? JsonPrimitive)?.contentOrNull
                    val name = (asn["name"] as? JsonPrimitive)?.contentOrNull ?: ""
                    append("ASN: AS$num $name\n")
                    (asn["description"] as? JsonPrimitive)?.contentOrNull?.let { append("Org: $it\n") }
                }
                (p["country_code"] as? JsonPrimitive)?.contentOrNull?.let { append("Country: $it\n") }
            }
            if (isBlank()) append(ripeRouting(ip))
        }
    }.orStop()

    /** Routing facts for an IP straight from RIPEstat — the fallback when
     *  BGPView is unavailable, which it often is. */
    private suspend fun ripeRouting(ip: String): String = runCatching {
        val enc = URLEncoder.encode(ip, "UTF-8")
        val net = json.parseToJsonElement(
            getBody("https://stat.ripe.net/data/network-info/data.json?resource=$enc")
        ).jsonObject["data"]?.jsonObject
        val prefix = (net?.get("prefix") as? JsonPrimitive)?.contentOrNull
        val asns = (net?.get("asns") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (prefix == null && asns.isEmpty()) return@runCatching "No routing data for $ip."
        // One lookup per ASN, asked together. An address announced
        // from four autonomous systems meant four round trips in a
        // row to the same host, which is the whole of this tool's
        // latency for four independent questions.
        val holders = coroutineScope {
            asns.map { a ->
                async {
                    a to runCatching {
                        (json.parseToJsonElement(
                            getBody("https://stat.ripe.net/data/as-overview/data.json?resource=AS$a")
                        ).jsonObject["data"]?.jsonObject?.get("holder") as? JsonPrimitive)?.contentOrNull
                    }.orStop().getOrNull()
                }
            }.map { it.await() }
        }
        buildString {
            append("IP: $ip\n")
            prefix?.let { append("Prefix: $it\n") }
            holders.forEach { (a, holder) ->
                append("ASN: AS$a")
                if (!holder.isNullOrBlank()) append(" $holder")
                append("\n")
            }
            append("Source: RIPEstat.")
        }
        // "No routing data" is one of the phrases that mean a source
        // looked and found nothing, so saying it here filed a dropped
        // connection as an established absence: the dossier reported
        // that RIPEstat had been consulted and the address is not
        // routed. The reason in the parentheses was never read by
        // anything. A failure has to say it could not be reached.
    }.orStop().getOrElse { "RIPEstat could not be reached for $ip (${it.message})." }

    /** Hudson Rock infostealer check (keyless): is this email in stealer logs. */
    fun hudsonRock(email: String): Result<String> = runCatching {
        val o = json.parseToJsonElement(
            getBody("https://cavalier.hudsonrock.com/api/json/v2/osint-tools/search-by-email?email=" + URLEncoder.encode(email, "UTF-8"))
        ).jsonObject
        val stealers = o["stealers"] as? JsonArray
        // The verdict is the first line, and the vendor's own prose
        // comes after it.
        //
        // It was the other way round. The app reads the first
        // non-blank line of a tool's output to decide whether the
        // tool found something, and Hudson Rock opens every reply —
        // including a clean one — with a sentence of its own about
        // its service. So an email with no infostealer record was
        // filed as a hit, counted as a corroborating source, and
        // written into the export as one.
        buildString {
            val n = stealers?.size ?: 0
            if (n > 0) {
                append("Infostealer infections: $n\n")
                stealers!!.take(6).forEach { s ->
                    val so = s.jsonObject
                    val fam = (so["stealer_family"] as? JsonPrimitive)?.contentOrNull ?: "stealer"
                    val date = (so["date_compromised"] as? JsonPrimitive)?.contentOrNull?.take(10) ?: ""
                    append("• $fam $date\n")
                }
                andMore(n, 6)
            } else {
                append("No infostealer records found for this email.\n")
            }
            (o["message"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { append("\nHudson Rock says: $it") }
        }
    }

    /** urlscan.io recent scans for a domain (keyless search API). */
    fun urlscan(domain: String): Result<String> = runCatching {
        val body = json.parseToJsonElement(
            getBody("https://urlscan.io/api/v1/search/?q=domain:" + URLEncoder.encode(domain, "UTF-8") + "&size=10")
        ).jsonObject
        // Same shape as wikiNearby: urlscan answers 200 with a
        // `message`/`status` body for a refused or rate-limited
        // query, and a missing `results` was being printed as "no
        // scans for this domain" — an absence the service never
        // asserted.
        val results = body["results"]?.jsonArray
        if (results == null) {
            val why = (body["message"] as? JsonPrimitive)?.contentOrNull
                ?: (body["description"] as? JsonPrimitive)?.contentOrNull
            throw java.io.IOException(
                "urlscan.io did not answer with results" +
                    if (why.isNullOrBlank()) "" else ": $why"
            )
        }
        if (results.isEmpty()) "No urlscan.io scans for $domain." else
            "urlscan.io — ${results.size} recent scans:\n" + results.take(10).joinToString("\n") { r ->
                val ro = r.jsonObject
                val pageUrl = ((ro["page"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull ?: ""
                val time = ((ro["task"] as? JsonObject)?.get("time") as? JsonPrimitive)?.contentOrNull?.take(10) ?: ""
                val scan = (ro["result"] as? JsonPrimitive)?.contentOrNull ?: ""
                "$pageUrl ($time)\n$scan"
            }
    }

    /** Real inline results from reliable keyless sources: Wikipedia full-text
     *  search (title + snippet + link) plus a best-effort DuckDuckGo instant
     *  answer. (The DDG HTML scrape serves a bot captcha, so it's not used.) */
    fun websearch(query: String): Result<String> = runCatching {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = StringBuilder()

        // Best-effort DuckDuckGo Instant Answer (quick facts; may be
        // empty). The whole query goes to them, email addresses
        // included — said in the registry entry so it is visible
        // before the tool is run rather than after.
        runCatching {
            val o = json.parseToJsonElement(
                getBody("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1")
            ).jsonObject
            (o["AbstractText"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                out.append(it).append("\n")
                (o["AbstractURL"] as? JsonPrimitive)?.contentOrNull?.takeIf { u -> u.isNotBlank() }
                    ?.let { u -> out.append(u).append("\n") }
            }
            (o["Answer"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { out.append("Answer: $it\n") }
        }

        // Wikipedia full-text search (reliable, keyless).
        runCatching {
            // Through MediaWiki, like the other four: this one does
            // not print a false absence (the section is simply
            // omitted), but a Wikipedia block vanishing from an
            // aggregate answer with no explanation is the same class,
            // and the runCatching around it records the reason.
            val hits = MediaWiki.query(
                getBody(
                    "https://en.wikipedia.org/w/api.php?action=query&list=search" +
                        "&srsearch=$q&format=json&srlimit=8"
                ),
                "Wikipedia search"
            )["search"]?.jsonArray
            if (hits != null && hits.isNotEmpty()) {
                out.append("\nWikipedia (${hits.size}):\n")
                hits.forEach { el ->
                    val eo = el.jsonObject
                    val title = (eo["title"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    val snippet = (eo["snippet"] as? JsonPrimitive)?.contentOrNull?.let { stripTags(it) }.orEmpty()
                    val url = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                    out.append("\n$title\n$url")
                    if (snippet.isNotBlank()) out.append("\n$snippet")
                }
            }
        }

        if (out.isBlank()) "No web/wiki results for \"$query\" — use the pivots below."
        else out.toString().trim()
    }

    /** Live GitHub profile for a username (keyless, 60 req/hr): name, bio,
     *  company, location, repos, followers, join date. */
    fun githubUser(username: String): Result<String> = runCatching {
        val u = username.substringBefore('@').trim()
        val o = json.parseToJsonElement(
            getBody("https://api.github.com/users/" + URLEncoder.encode(u, "UTF-8"))
        ).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        if (s("login") == null) return@runCatching "No GitHub user \"$u\"."
        buildString {
            append("GitHub: @${s("login")}\n")
            s("name")?.let { append("Name: $it\n") }
            s("bio")?.let { append("Bio: $it\n") }
            s("company")?.let { append("Company: $it\n") }
            s("location")?.let { append("Location: $it\n") }
            s("email")?.let { append("Email: $it\n") }
            s("blog")?.takeIf { it.isNotBlank() }?.let { append("Site: $it\n") }
            s("twitter_username")?.let { append("Twitter: @$it\n") }
            append("Repos: ${s("public_repos") ?: "?"} · Followers: ${s("followers") ?: "?"} · Following: ${s("following") ?: "?"}\n")
            s("created_at")?.let { append("Joined: ${it.take(10)}\n") }
            append("Profile: https://github.com/${s("login")}")
        }
    }

    private fun stripTags(s: String) = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"").replace("&#39;", "'")
        .trim()

    /** Builds a rich, ready-to-tap OSINT pivot sheet tailored to the input
     *  type: platform profiles, people-search / public-records engines, breach
     *  checks, dork queries and recon links. All links, no API dependency. */
    fun dorks(value: String): Result<String> = runCatching {
        val v = value.trim()
        fun enc(q: String) = URLEncoder.encode(q, "UTF-8")
        fun g(q: String) = "https://www.google.com/search?q=${enc(q)}"
        val lines = mutableListOf<String>()
        fun add(label: String, url: String) = lines.add("$label: $url")

        val isEmail = v.contains('@') && !v.contains(' ')
        // An address is not a telephone number. The router has carried
        // this guard for a while; these two copies of the shape test
        // never got it, and the phone branch is tested first — so
        // "8.8.8.8" produced a reverse-lookup dork against Truecaller
        // and Whitepages, and social-media searches for a person who
        // does not exist.
        val isIpv4 = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(v)
        val isPhone = !isIpv4 && Regex("""^\+?[\d][\d\s\-().]{6,}$""").matches(v)
        // An address has dots in it and no spaces, which is the whole
        // of the domain test — so the phone branch was taught to
        // stand aside for an IPv4 and the domain branch, three lines
        // below it, caught it instead: site:8.8.8.8, a wildcard
        // subdomain search, and a certificate-transparency query for
        // an address that cannot appear on a certificate as a name.
        val isDomain = !isEmail && !isIpv4 && v.contains('.') && !v.contains(' ')
        val isName = v.contains(' ')
        val handle = v.substringBefore('@').replace(" ", "").lowercase()

        when {
            isEmail -> {
                add("Exact mentions", g("\"$v\""))
                add("Have I Been Pwned", "https://haveibeenpwned.com/account/${enc(v)}")
                add("Paste / code", g("\"$v\" (site:pastebin.com OR site:github.com OR site:gist.github.com)"))
                add("Documents", g("\"$v\" (filetype:pdf OR filetype:xls OR filetype:docx)"))
                add("Google", g(v))
            }
            isPhone -> {
                val digits = v.filter { it.isDigit() || it == '+' }
                add("Exact number", g("\"$v\""))
                add("Digits", g("\"$digits\""))
                add("Reverse lookup", g("$digits (site:truecaller.com OR site:whitepages.com OR site:spydialer.com)"))
                add("Social mentions", g("\"$v\" (site:facebook.com OR site:linkedin.com)"))
            }
            isIpv4 -> {
                add("Mentions", g("\"$v\""))
                add("Reverse DNS / routing", "https://bgp.he.net/ip/$v")
                add("Network and owner", "https://ipinfo.io/$v")
                add("Exposed services", "https://www.shodan.io/host/$v")
                add("Abuse reports", "https://www.abuseipdb.com/check/$v")
                add("Hosted names", g("\"$v\" (site:viewdns.info OR site:securitytrails.com)"))
            }
            isDomain -> {
                add("Site contents", g("site:$v"))
                add("Subdomains", g("site:*.$v"))
                add("Exposed docs", g("site:$v (filetype:pdf OR filetype:xls OR filetype:docx OR filetype:csv)"))
                add("Directory listings", g("site:$v intitle:\"index of\""))
                add("Login / admin", g("site:$v (inurl:login OR inurl:admin OR inurl:dashboard)"))
                add("Config / backups", g("site:$v (ext:env OR ext:bak OR ext:sql OR ext:log OR ext:conf)"))
                add("crt.sh certs", "https://crt.sh/?q=${enc(v)}")
                add("urlscan.io", "https://urlscan.io/domain/${seg(v)}")
                add("Wayback", "https://web.archive.org/web/*/${seg(v)}*")
            }
            isName -> {
                add("Exact (Google)", g("\"$v\""))
                add("Bing", "https://www.bing.com/search?q=${enc("\"$v\"")}")
                add("DuckDuckGo", "https://duckduckgo.com/?q=${enc("\"$v\"")}")
                add("LinkedIn", g("site:linkedin.com/in \"$v\""))
                add("Facebook", g("site:facebook.com \"$v\""))
                add("X / Twitter", g("(site:twitter.com OR site:x.com) \"$v\""))
                add("Instagram", g("site:instagram.com \"$v\""))
                add("TikTok", g("site:tiktok.com \"$v\""))
                add("TruePeopleSearch", "https://www.truepeoplesearch.com/results?name=${enc(v)}")
                add("FastPeopleSearch", "https://www.fastpeoplesearch.com/name/${enc(v.replace(" ", "-"))}")
                add("ThatsThem", "https://thatsthem.com/name/${enc(v.replace(" ", "-"))}")
                add("Documents", g("\"$v\" (filetype:pdf OR filetype:docx)"))
            }
            else -> {
                add("GitHub", "https://github.com/${seg(handle)}")
                add("X / Twitter", "https://x.com/${seg(handle)}")
                add("Instagram", "https://www.instagram.com/${seg(handle)}")
                add("Reddit", "https://www.reddit.com/user/${seg(handle)}")
                add("TikTok", "https://www.tiktok.com/@${seg(handle)}")
                add("YouTube", "https://www.youtube.com/@${seg(handle)}")
                // Encoded like the six above it. These two were
                // raw interpolation, so a handle with a slash or a
                // query character in it built a different URL from
                // the one the label claimed.
                add("Twitch", "https://www.twitch.tv/${seg(handle)}")
                add("Telegram", "https://t.me/${seg(handle)}")
                add("Mentions", g("\"$handle\""))
                add("Paste / code", g("\"$handle\" (site:pastebin.com OR site:github.com)"))
            }
        }
        "${lines.size} pivots:\n" + lines.joinToString("\n")
    }

    /** Photon-style crawl: fetch a page and extract emails, links and JS files. */
    fun webcrawl(input: String): Result<String> = runCatching {
        val url = withScheme(input)
        val html = getBody(url)
        // Hostnames.emailsIn, not the pattern that used to be here.
        //
        // Five copies of `[A-Za-z0-9._%+-]+@…` were replaced when it
        // was measured at fifty-odd seconds over a 96 KB body; this
        // one was missed, and it is the worst-placed of the seven. It
        // runs over a whole fetched page — content the subject chose,
        // from a URL pointed at the subject's own site — with no scan
        // cap at all, and a hundred kilobytes of local-part characters
        // with no `@` in them makes every start position walk the rest
        // of the run. The replacement finds the `@` first and grows
        // outwards, which is linear.
        //
        // regex-recursion could not see it: that check hunts unbounded
        // group repetition and this is a character class.
        val emails = Hostnames.emailsIn(html).distinct().take(30)
        val links = Regex("""href=["']([^"']+)["']""")
            .findAll(html).map { it.groupValues[1] }.filter { it.startsWith("http") }.distinct().take(40).toList()
        val scripts = Regex("""src=["']([^"']+\.js[^"']*)["']""")
            .findAll(html).map { it.groupValues[1] }.distinct().take(20).toList()
        if (emails.isEmpty() && links.isEmpty() && scripts.isEmpty()) {
            // "Crawled: https://…" is a statement about what this app
            // did, not about the target, and the classifier read it as
            // a finding — so a page with nothing on it counted among
            // the sources that returned something, under two empty
            // headed sections.
            return@runCatching "No emails, links or scripts found on $url."
        }
        buildString {
            append("Crawled: $url\n")
            if (emails.isNotEmpty()) {
                append("\nEmails (${emails.size}):\n").append(emails.joinToString("\n"))
            }
            if (links.isNotEmpty()) {
                append("\n\nLinks (${links.size}")
                if (links.size >= 40) append(", the first 40 found")
                append("):\n").append(links.joinToString("\n"))
            }
            if (scripts.isNotEmpty()) append("\n\nJS files (${scripts.size}):\n").append(scripts.joinToString("\n"))
        }
    }

    /**
     * A URL with a scheme on the front, when the input had none.
     *
     * `startsWith("http")` is not a scheme test: httpbin.org,
     * httptoolkit.com and https.dev all begin with those letters and
     * were handed to OkHttp without a scheme, which throws.
     */
    private fun withScheme(input: String): String {
        val v = input.trim()
        return if (v.startsWith("http://", true) || v.startsWith("https://", true)) {
            v
        } else {
            "https://$v"
        }
    }

    /** The latest archived copy of a URL (availability API — reliable, unlike
     *  Save Page Now which frequently returns 503). */
    fun archive(input: String): Result<String> = runCatching { waybackLatest(input) }

    /** Forward geocoding: a place name -> matching locations with coordinates. */
    fun geocode(place: String): Result<String> = runCatching {
        val url = "https://nominatim.openstreetmap.org/search?q=" +
            URLEncoder.encode(place, "UTF-8") + "&format=jsonv2&limit=5"
        val arr = json.parseToJsonElement(getBody(url)).jsonArray
        if (arr.isEmpty()) "No places matched \"$place\"." else
            "Matched ${arr.size} place(s):\n\n" + arr.joinToString("\n\n") { el ->
                val o = el.jsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val name = s("display_name") ?: "?"
                val lat = s("lat").orEmpty()
                val lon = s("lon").orEmpty()
                "$name\nCoords: $lat, $lon\nMap: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=14/$lat/$lon"
            }
    }

    /** Wikipedia GeoSearch: notable places near a coordinate. */
    fun wikiNearby(input: String): Result<String> = runCatching {
        // Parsed as numbers before they go into a URL.
        //
        // This split on a comma and interpolated both halves
        // straight into the query string, so anything the user typed
        // — an ampersand, a fragment of another parameter — became
        // part of the request.
        val here = com.osint.unified.domain.Coordinates.parse(input)
        require(here != null) {
            "Expected 'lat, lon', with latitude in -90..90 and longitude " +
                "in -180..180."
        }
        val lat = here.lat
        val lon = here.lon
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=geosearch" +
            "&gscoord=%.6f%%7C%.6f".format(java.util.Locale.US, lat, lon) +
            "&gsradius=10000&gslimit=25&format=json"
        // An error is not an absence — see MediaWiki, where the
        // test now lives so that all five callers share it.
        val arr = MediaWiki.query(getBody(url), "Wikipedia nearby")["geosearch"]?.jsonArray
            ?: throw java.io.IOException("Wikipedia nearby: no geosearch block")
        if (arr.isEmpty()) "No Wikipedia places within 10 km." else
            "Nearby notable places (${arr.size}):\n\n" + arr.joinToString("\n") { el ->
                val o = el.jsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val title = s("title") ?: "?"
                val plat = s("lat").orEmpty()
                val plon = s("lon").orEmpty()
                val dist = (o["dist"] as? JsonPrimitive)?.contentOrNull
                val slug = URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                "• $title${if (dist != null) " (${dist}m)" else ""}\n  Coords: $plat, $plon\n  https://en.wikipedia.org/wiki/$slug"
            }
    }

    /** GDELT 2.0 geolocated news: places in global news mentioning the query. */
    /**
     * Global news coverage for a query, from GDELT's article API.
     *
     * GDELT's geo endpoint — which returned mapped hotspots — now answers
     * 404 to every request; it has been retired. The article API still
     * works, so this returns coverage with sources, countries and dates
     * instead of silently showing an empty map layer. GDELT asks for no
     * more than one request every five seconds, so a 429 is reported as
     * rate limiting rather than as "no results".
     */
    fun gdeltNews(query: String): Result<String> = runCatching {
        val url = "https://api.gdeltproject.org/api/v2/doc/doc?query=" +
            URLEncoder.encode(query, "UTF-8") +
            "&mode=artlist&format=json&maxrecords=75&sort=datedesc&timespan=3d"
        val body = runCatching { getBody(url) }.orStop().getOrElse { e ->
            return@runCatching if (e.message?.contains("429") == true) {
                "GDELT is rate limiting (it allows one request every five seconds). Try again shortly."
            } else {
                // Neither an absence nor a finding until this said
                // which: it was in no list at all, so a failed call
                // was filed as a hit and counted among the sources
                // that returned something.
                "GDELT could not be reached for \"$query\"."
            }
        }
        if (body.trimStart().startsWith("Please limit")) {
            return@runCatching "GDELT is rate limiting (one request per five seconds). Try again shortly."
        }
        // A body that will not parse is not an empty result.
        //
        // GDELT answers 200 with plain text for several errors besides
        // the rate limit special-cased above — a query it cannot
        // parse, an unsupported operator, maintenance. The parse threw
        // inside runCatching, `articles` came out null, and the line
        // below asserted that nothing in the world's news had
        // mentioned the target in three days.
        val parsed = runCatching {
            json.parseToJsonElement(body).jsonObject["articles"] as? JsonArray
        }
        if (parsed.isFailure) {
            return@runCatching "GDELT could not be read for \"$query\" — it answered " +
                "with something that is not its usual result: " +
                body.trim().take(120).replace('\n', ' ')
        }
        val articles = parsed.getOrNull()
        if (articles.isNullOrEmpty()) {
            return@runCatching "No news coverage for \"$query\" in the last three days."
        }
        val byCountry = LinkedHashMap<String, Int>()
        val parsedArticles = articles.mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            fun f(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val title = f("title")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            f("sourcecountry")?.takeIf { it.isNotBlank() }?.let { byCountry[it] = (byCountry[it] ?: 0) + 1 }
            com.osint.unified.domain.StoryClusters.Article(
                title = title,
                domain = f("domain")?.takeIf { it.isNotBlank() },
                url = f("url"),
                seen = f("seendate"),
                country = f("sourcecountry")
            )
        }
        // Grouped, not listed.
        //
        // Seventy-five headlines is not a briefing: most of them are
        // the same three stories rewritten, and the thing worth
        // knowing — that one is carried by thirty outlets and
        // another by one — cannot be seen in a flat list sorted by
        // time. Ordered by how many independent publishers carry a
        // story, which is the same reasoning the corroboration pass
        // uses everywhere else in this app.
        val clusters = com.osint.unified.domain.StoryClusters.cluster(parsedArticles)
        buildString {
            // "at least", when the request's own cap is what the
            // count reflects. maxrecords=75 is the ceiling, and
            // reporting it as a total told the reader there were
            // exactly seventy-five.
            val more = if (articles.size >= 75) "at least " else ""
            append("News coverage for \"$query\" — $more${articles.size} articles")
            append(" in ${clusters.size} ")
            append(if (clusters.size == 1) "story" else "distinct stories")
            append(", last 3 days\n")
            if (byCountry.isNotEmpty()) {
                append("\nReporting countries: ")
                append(
                    byCountry.entries.sortedByDescending { it.value }.take(12)
                        .joinToString(", ") { "${it.key} (${it.value})" }
                )
                append("\n")
            }
            clusters.take(20).forEach { c ->
                append("\n▸ ${c.headline}\n")
                append("  ${c.publishers} ")
                append(if (c.publishers == 1) "publisher" else "publishers")
                val from = com.osint.unified.domain.StoryClusters.day(c.first)
                val to = com.osint.unified.domain.StoryClusters.day(c.last)
                if (from != null) {
                    if (to != null && to != from) append(", $from to $to")
                    else append(", $from")
                }
                if (c.countries.size > 1) {
                    append(", ${c.countries.size} countries")
                }
                append("\n")
                // One line per publisher, not per article: the same
                // outlet running a story four times is one outlet,
                // and printing it four times reads as four sources.
                c.articles.distinctBy { it.domain ?: it.url }.take(6).forEach { a ->
                    append("  · ${a.domain ?: "?"}")
                    com.osint.unified.domain.StoryClusters.day(a.seen)?.let { append("  $it") }
                    a.url?.let { append("\n    $it") }
                    append("\n")
                }
                val outlets = c.articles.distinctBy { it.domain ?: it.url }.size
                if (outlets > 6) append("  …and ${outlets - 6} more publishers.\n")
            }
            if (clusters.size > 20) {
                append("\n…and ${clusters.size - 20} smaller stories.\n")
            }
            append("\nA story one outlet carries and a story thirty carry are ")
            append("different facts about the world, which is why these are ")
            append("ordered by publishers rather than by time. Outlets ")
            append("republishing the same wire copy are not independent ")
            append("confirmation of each other.\n")
        }
    }

    /** Reverse image search links across the major engines. */
    fun reverseImage(url: String): Result<String> = runCatching {
        val e = URLEncoder.encode(url, "UTF-8")
        // Free reverse-image engines only (no paywalled services). Yandex is
        // the strongest of these for faces.
        buildString {
            append("Reverse image search (all free):\n")
            append("Google Lens: https://lens.google.com/uploadbyurl?url=$e\n")
            append("Yandex (best for faces): https://yandex.com/images/search?rpt=imageview&url=$e\n")
            append("Bing: https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:$e\n")
            append("TinEye: https://tineye.com/search?url=$e\n")
        }
    }

    /**
     * The HTTP status behind a failed [getBody], when there was one.
     *
     * getBody reports a non-2xx as IOException("HTTP nnn"); a socket
     * error has no code. The difference is "the site answered" and
     * "the site did not", which is the difference between an absence
     * and a shrug.
     */
    private fun httpCodeOf(e: Throwable?): Int? = (e as? IOException)?.message
        ?.takeIf { it.startsWith("HTTP ") }?.removePrefix("HTTP ")?.trim()?.toIntOrNull()

    private val ipRegex = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")

    private fun resolveA(domain: String): String? = runCatching {
        json.parseToJsonElement(
            getBody(
                "https://dns.google/resolve?type=A&name=" +
                    URLEncoder.encode(domain, "UTF-8")
            )
        )
            .jsonObject["Answer"]?.jsonArray
            ?.firstNotNullOfOrNull { el ->
                val o = el as? JsonObject
                if ((o?.get("type") as? JsonPrimitive)?.contentOrNull == "1") {
                    (o["data"] as? JsonPrimitive)?.contentOrNull
                } else null
            }
    }.getOrNull()

    private fun getBody(url: String, patient: Boolean = false): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", agentFor(url, ua)).build()
        val c = if (patient) com.osint.unified.network.Http.slowApiClient else client
        c.newCall(request).execute().use { resp ->
            // Bounded. These go to whatever the user typed, and
            // body.string() reads all of it: a certificate
            // transparency query for a large domain is tens of
            // megabytes, and a URL that turns out to be a video is
            // however long the video is. The connection closes at
            // the limit, so the rest never crosses the network.
            val body = resp.body?.bodyText().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }

    /** Percent-encodes a value for use inside a displayed URL path, so a name
     *  with a space still produces an openable link. */
    private fun seg(v: String): String =
        URLEncoder.encode(v.trim(), "UTF-8").replace("+", "%20")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
