package com.osint.unified.domain.executor

import com.osint.unified.network.Http
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
    private val ua = "OSINT-Unified/1.0 (research)"

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

        val crtFailure = runCatching {
            val url = "https://crt.sh/?q=" + URLEncoder.encode("%.$d", "UTF-8") + "&output=json"
            json.parseToJsonElement(getBody(url, patient = true)).jsonArray.forEach { el ->
                ((el as? JsonObject)?.get("name_value") as? JsonPrimitive)?.contentOrNull
                    ?.let(::record)
            }
        }.exceptionOrNull()
        val fromCrtSh = subs.size

        val spotterFailure = runCatching {
            val url = "https://api.certspotter.com/v1/issuances?domain=$d" +
                "&include_subdomains=true&expand=dns_names"
            json.parseToJsonElement(getBody(url)).jsonArray.forEach { el ->
                ((el as? JsonObject)?.get("dns_names") as? JsonArray)?.forEach { n ->
                    (n as? JsonPrimitive)?.contentOrNull?.let(::record)
                }
            }
        }.exceptionOrNull()

        when {
            subs.isNotEmpty() -> buildString {
                append("Certificate transparency — ${subs.size} names for $d")
                append(" (crt.sh $fromCrtSh")
                append(", Cert Spotter +${subs.size - fromCrtSh}")
                append(")")
                if (crtFailure != null) append("\ncrt.sh did not answer: ${crtFailure.message}")
                append("\n")
                append(subs.sorted().joinToString("\n"))
            }
            crtFailure != null && spotterFailure != null ->
                "Certificate transparency unreachable — crt.sh: ${crtFailure.message}; " +
                    "Cert Spotter: ${spotterFailure.message}"
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
        json.parseToJsonElement(getBody("https://dns.google/resolve?name=$name&type=$type"))
            .jsonObject["Answer"]?.jsonArray?.mapNotNull {
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
            val policy = Regex("p=(\\w+)").find(dmarc.firstOrNull().orEmpty())?.groupValues?.get(1)
            append("Spoofing risk: ${when {
                spf.isEmpty() && dmarc.isEmpty() -> "HIGH (no SPF or DMARC)"
                dmarc.isEmpty() || policy == "none" -> "MEDIUM (no enforced DMARC)"
                else -> "LOW (DMARC p=$policy)"
            }}")
        }
    }

    /** HTTP security headers + server banner for a domain/URL. */
    fun securityHeaders(input: String): Result<String> = runCatching {
        val url = if (input.startsWith("http")) input else "https://$input"
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
        val arpa = ip.trim().split('.').reversed().joinToString(".") + ".in-addr.arpa"
        val ptr = dohTxt(arpa, "PTR")
        if (ptr.isEmpty()) "No PTR record for $ip." else "PTR: ${ptr.joinToString(", ")}"
    }

    private fun hostOf(input: String) =
        input.removePrefix("https://").removePrefix("http://").substringBefore('/').trim()

    /** TLS certificate: issuer, validity window, days left, and every hostname
     *  the cert covers (SANs) — read straight from the handshake. */
    fun tlsCert(input: String): Result<String> = runCatching {
        val host = hostOf(input)
        val request = Request.Builder().url("https://$host").head().header("User-Agent", ua).build()
        client.newCall(request).execute().use { resp ->
            val hs = resp.handshake ?: return@runCatching "No TLS handshake with $host."
            val cert = hs.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                ?: return@runCatching "No certificate presented by $host."
            val days = (cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L
            buildString {
                append("Host: $host\n")
                append("Issuer: ${cert.issuerX500Principal.name}\n")
                append("Subject: ${cert.subjectX500Principal.name}\n")
                append("Valid from: ${cert.notBefore}\n")
                append("Expires: ${cert.notAfter}\n")
                append("Days remaining: $days\n")
                append("TLS: ${hs.tlsVersion} / ${hs.cipherSuite}\n")
                val sans = runCatching {
                    cert.subjectAlternativeNames?.mapNotNull { (it as? List<*>)?.getOrNull(1) as? String }
                }.getOrNull().orEmpty()
                if (sans.isNotEmpty()) append("Covers ${sans.size} hostnames: ${sans.take(40).joinToString(", ")}\n")
            }
        }
    }

    /** robots.txt, security.txt and sitemap — the files a site publishes about
     *  itself, often naming paths it would rather not advertise. */
    fun siteFiles(input: String): Result<String> = runCatching {
        val host = hostOf(input)
        val out = StringBuilder()
        listOf("robots.txt", ".well-known/security.txt", "security.txt", "sitemap.xml").forEach { path ->
            runCatching {
                val body = getBody("https://$host/$path")
                if (body.isNotBlank() && !body.take(200).contains("<html", true)) {
                    out.append("--- /$path ---\n")
                    out.append(body.lineSequence().filter { it.isNotBlank() }.take(25).joinToString("\n"))
                    out.append("\n\n")
                }
            }
        }
        if (out.isBlank()) "No robots.txt, security.txt or sitemap found on $host."
        else out.toString().trim()
    }

    /** Is this IP a Tor exit node? (official bulk exit list, keyless) */
    fun torExit(ip: String): Result<String> = runCatching {
        val target = ip.trim()
        val list = getBody("https://check.torproject.org/torbulkexitlist")
        val exits = list.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (exits.contains(target)) "⚠ $target IS a Tor exit node (anonymised traffic)."
        else "$target is not a Tor exit node (checked ${exits.size} known exits)."
    }

    /** DNS-over-HTTPS record lookup (Google DoH). */
    fun dns(domain: String): Result<String> = runCatching {
        val sb = StringBuilder()
        listOf("A", "AAAA", "MX", "NS", "TXT").forEach { type ->
            val answers = runCatching {
                json.parseToJsonElement(getBody("https://dns.google/resolve?name=$domain&type=$type"))
                    .jsonObject["Answer"]?.jsonArray
            }.getOrNull()
            val values = answers?.mapNotNull {
                ((it as? JsonObject)?.get("data") as? JsonPrimitive)?.contentOrNull
            } ?: emptyList()
            if (values.isNotEmpty()) sb.append("$type: ${values.joinToString(", ")}\n")
        }
        if (sb.isBlank()) "No DNS records found." else sb.toString()
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
        val target = if (input.startsWith("http")) input else "https://$input"
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
        val parts = input.split(",").map { it.trim() }
        require(parts.size == 2) { "Expected 'lat, lon'." }
        val lat = parts[0].toDouble()
        val lon = parts[1].toDouble()
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
    fun bgpview(ip: String): Result<String> = runCatching {
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
    }

    /** Routing facts for an IP straight from RIPEstat — the fallback when
     *  BGPView is unavailable, which it often is. */
    private fun ripeRouting(ip: String): String = runCatching {
        val enc = URLEncoder.encode(ip, "UTF-8")
        val net = json.parseToJsonElement(
            getBody("https://stat.ripe.net/data/network-info/data.json?resource=$enc")
        ).jsonObject["data"]?.jsonObject
        val prefix = (net?.get("prefix") as? JsonPrimitive)?.contentOrNull
        val asns = (net?.get("asns") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (prefix == null && asns.isEmpty()) return@runCatching "No routing data for $ip."
        buildString {
            append("IP: $ip\n")
            prefix?.let { append("Prefix: $it\n") }
            asns.forEach { a ->
                val holder = runCatching {
                    (json.parseToJsonElement(
                        getBody("https://stat.ripe.net/data/as-overview/data.json?resource=AS$a")
                    ).jsonObject["data"]?.jsonObject?.get("holder") as? JsonPrimitive)?.contentOrNull
                }.getOrNull()
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
    }.getOrElse { "RIPEstat could not be reached for $ip (${it.message})." }

    /** Hudson Rock infostealer check (keyless): is this email in stealer logs. */
    fun hudsonRock(email: String): Result<String> = runCatching {
        val o = json.parseToJsonElement(
            getBody("https://cavalier.hudsonrock.com/api/json/v2/osint-tools/search-by-email?email=" + URLEncoder.encode(email, "UTF-8"))
        ).jsonObject
        val stealers = o["stealers"] as? JsonArray
        buildString {
            (o["message"] as? JsonPrimitive)?.contentOrNull?.let { append("$it\n") }
            if (stealers != null && stealers.isNotEmpty()) {
                append("Infostealer infections: ${stealers.size}\n")
                stealers.take(6).forEach { s ->
                    val so = s.jsonObject
                    val fam = (so["stealer_family"] as? JsonPrimitive)?.contentOrNull ?: "stealer"
                    val date = (so["date_compromised"] as? JsonPrimitive)?.contentOrNull?.take(10) ?: ""
                    append("• $fam $date\n")
                }
            }
            if (isBlank()) append("No infostealer records found for this email.")
        }
    }

    /** urlscan.io recent scans for a domain (keyless search API). */
    fun urlscan(domain: String): Result<String> = runCatching {
        val results = json.parseToJsonElement(
            getBody("https://urlscan.io/api/v1/search/?q=domain:" + URLEncoder.encode(domain, "UTF-8") + "&size=10")
        ).jsonObject["results"]?.jsonArray
        if (results == null || results.isEmpty()) "No urlscan.io scans for $domain." else
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
            val w = json.parseToJsonElement(
                getBody("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&format=json&srlimit=8")
            ).jsonObject
            val hits = w["query"]?.jsonObject?.get("search")?.jsonArray
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
        val isDomain = !isEmail && v.contains('.') && !v.contains(' ')
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
                add("Twitch", "https://www.twitch.tv/$handle")
                add("Telegram", "https://t.me/$handle")
                add("Mentions", g("\"$handle\""))
                add("Paste / code", g("\"$handle\" (site:pastebin.com OR site:github.com)"))
            }
        }
        "${lines.size} pivots:\n" + lines.joinToString("\n")
    }

    /** Photon-style crawl: fetch a page and extract emails, links and JS files. */
    fun webcrawl(input: String): Result<String> = runCatching {
        val url = if (input.startsWith("http")) input else "https://$input"
        val html = getBody(url)
        val emails = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
            .findAll(html).map { it.value }.distinct().take(30).toList()
        val links = Regex("""href=["']([^"']+)["']""")
            .findAll(html).map { it.groupValues[1] }.filter { it.startsWith("http") }.distinct().take(40).toList()
        val scripts = Regex("""src=["']([^"']+\.js[^"']*)["']""")
            .findAll(html).map { it.groupValues[1] }.distinct().take(20).toList()
        buildString {
            append("Crawled: $url\n")
            append("\nEmails (${emails.size}):\n").append(emails.joinToString("\n"))
            append("\n\nLinks (${links.size}):\n").append(links.joinToString("\n"))
            if (scripts.isNotEmpty()) append("\n\nJS files (${scripts.size}):\n").append(scripts.joinToString("\n"))
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
        val parts = input.split(",").map { it.trim() }
        require(parts.size == 2) { "Expected 'lat, lon'." }
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=geosearch" +
            "&gscoord=${parts[0]}%7C${parts[1]}&gsradius=10000&gslimit=25&format=json"
        val arr = json.parseToJsonElement(getBody(url))
            .jsonObject["query"]?.jsonObject?.get("geosearch")?.jsonArray
        if (arr == null || arr.isEmpty()) "No Wikipedia places within 10 km." else
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
        val body = runCatching { getBody(url) }.getOrElse { e ->
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
        val articles = runCatching {
            json.parseToJsonElement(body).jsonObject["articles"] as? JsonArray
        }.getOrNull()
        if (articles.isNullOrEmpty()) {
            return@runCatching "No news coverage for \"$query\" in the last three days."
        }
        val byCountry = LinkedHashMap<String, Int>()
        val lines = articles.take(50).mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            fun f(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val title = f("title")?.trim() ?: return@mapNotNull null
            f("sourcecountry")?.takeIf { it.isNotBlank() }?.let { byCountry[it] = (byCountry[it] ?: 0) + 1 }
            buildString {
                append("• $title")
                f("domain")?.let { append("\n  $it") }
                f("seendate")?.let { append("  ${it.take(8)}") }
                f("url")?.let { append("\n  $it") }
            }
        }
        buildString {
            append("News coverage for \"$query\" — ${articles.size} articles, last 3 days\n")
            if (byCountry.isNotEmpty()) {
                append("\nReporting countries: ")
                append(
                    byCountry.entries.sortedByDescending { it.value }.take(12)
                        .joinToString(", ") { "${it.key} (${it.value})" }
                )
                append("\n")
            }
            append("\n")
            append(lines.joinToString("\n"))
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

    private val ipRegex = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")

    private fun resolveA(domain: String): String? = runCatching {
        json.parseToJsonElement(getBody("https://dns.google/resolve?name=$domain&type=A"))
            .jsonObject["Answer"]?.jsonArray
            ?.firstNotNullOfOrNull { el ->
                val o = el as? JsonObject
                if ((o?.get("type") as? JsonPrimitive)?.contentOrNull == "1") {
                    (o["data"] as? JsonPrimitive)?.contentOrNull
                } else null
            }
    }.getOrNull()

    private fun getBody(url: String, patient: Boolean = false): String {
        val request = Request.Builder().url(url).header("User-Agent", ua).build()
        val c = if (patient) com.osint.unified.network.Http.slowApiClient else client
        c.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
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
