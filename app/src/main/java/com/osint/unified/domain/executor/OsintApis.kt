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

    /** crt.sh certificate-transparency subdomain discovery. */
    fun crtsh(domain: String): Result<String> = runCatching {
        val url = "https://crt.sh/?q=" + URLEncoder.encode("%.$domain", "UTF-8") + "&output=json"
        val subs = LinkedHashSet<String>()
        json.parseToJsonElement(getBody(url)).jsonArray.forEach { el ->
            ((el as? JsonObject)?.get("name_value") as? JsonPrimitive)?.contentOrNull
                ?.split("\n")?.forEach { line ->
                    val v = line.trim().removePrefix("*.").lowercase()
                    if (v.isNotEmpty()) subs.add(v)
                }
        }
        if (subs.isEmpty()) "crt.sh: no certificates found." else "crt.sh — ${subs.size} names:\n" + subs.sorted().joinToString("\n")
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
        buildString {
            append("IP: ${s("ip")}\n")
            append("Location: ${s("city")}, ${s("region")}, ${s("country")}\n")
            append("Coordinates: ${s("latitude")}, ${s("longitude")}\n")
            append("ISP: ${c("isp")}\n")
            append("Org: ${c("org")}\n")
            append("ASN: ${c("asn")}\n")
        }
    }

    /** Gravatar profile lookup by email hash. */
    fun gravatar(email: String): Result<String> = runCatching {
        val hash = md5(email.trim().lowercase())
        val avatar = "https://www.gravatar.com/avatar/$hash"
        val request = Request.Builder().url("https://www.gravatar.com/$hash.json").header("User-Agent", ua).build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) {
                "No Gravatar profile.\nAvatar (default if unset): $avatar"
            } else if (resp.isSuccessful) {
                "Gravatar profile found.\nAvatar: $avatar\n" + (resp.body?.string().orEmpty().take(800))
            } else {
                throw IOException("HTTP ${resp.code}")
            }
        }
    }

    /** Wayback Machine snapshot history for a domain. */
    fun wayback(domain: String): Result<String> = runCatching {
        val url = "https://web.archive.org/cdx/search/cdx?url=" +
            URLEncoder.encode("$domain*", "UTF-8") + "&output=json&limit=20&collapse=urlkey&fl=timestamp,original"
        val rows = json.parseToJsonElement(getBody(url)).jsonArray.drop(1)
        if (rows.isEmpty()) "No Wayback snapshots." else "Wayback — ${rows.size} snapshots:\n" +
            rows.joinToString("\n") { r ->
                val a = r.jsonArray
                val ts = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: ""
                val orig = (a.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: ""
                "https://web.archive.org/web/$ts/$orig"
            }
    }

    /** Reverse geocoding (OpenStreetMap Nominatim). */
    fun revgeo(input: String): Result<String> = runCatching {
        val parts = input.split(",").map { it.trim() }
        require(parts.size == 2) { "Expected 'lat, lon'." }
        val o = json.parseToJsonElement(
            getBody("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${parts[0]}&lon=${parts[1]}")
        ).jsonObject
        val display = (o["display_name"] as? JsonPrimitive)?.contentOrNull ?: "(no address found)"
        "Address: $display"
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

    /** Google dork generator: builds ready-to-open search URLs for exposed
     *  files, directories and mentions. */
    fun dorks(value: String): Result<String> = runCatching {
        val v = value.trim()
        val isDomain = v.contains('.') && !v.contains(' ') && !v.contains('@')
        val queries = mutableListOf<Pair<String, String>>()
        queries.add("Exact mentions" to "\"$v\"")
        if (isDomain) {
            queries.add("Site contents" to "site:$v")
            queries.add("Exposed documents" to "site:$v (filetype:pdf OR filetype:xls OR filetype:docx OR filetype:csv)")
            queries.add("Directory listings" to "site:$v intitle:\"index of\"")
            queries.add("Login / admin" to "site:$v (inurl:login OR inurl:admin OR inurl:dashboard)")
            queries.add("Config / backups" to "site:$v (ext:env OR ext:bak OR ext:sql OR ext:log OR ext:conf)")
        } else {
            if (v.contains(' ')) {
                queries.add("LinkedIn" to "site:linkedin.com/in \"$v\"")
                queries.add("Facebook" to "site:facebook.com \"$v\"")
                queries.add("X / Twitter" to "(site:twitter.com OR site:x.com) \"$v\"")
                queries.add("Instagram" to "site:instagram.com \"$v\"")
            }
            queries.add("In URLs" to "inurl:$v")
            queries.add("In titles" to "intitle:$v")
            queries.add("Paste / code sites" to "\"$v\" (site:pastebin.com OR site:github.com OR site:gist.github.com)")
            queries.add("In documents" to "\"$v\" (filetype:pdf OR filetype:xls OR filetype:docx)")
        }
        "Google dork URLs:\n" + queries.joinToString("\n") { (label, q) ->
            "$label: https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        }
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

    /** Preserve a URL via the Wayback Machine's Save Page Now. */
    fun archive(input: String): Result<String> = runCatching {
        val target = if (input.startsWith("http")) input else "https://$input"
        val request = Request.Builder().url("https://web.archive.org/save/$target").header("User-Agent", ua).build()
        client.newCall(request).execute().use { resp ->
            val loc = resp.header("Content-Location") ?: resp.header("Location")
            if (loc != null && loc.startsWith("/web/")) "Archived snapshot:\nhttps://web.archive.org$loc"
            else "Archive requested for $target (HTTP ${resp.code}).\nView: https://web.archive.org/web/2/$target"
        }
    }

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

    /** Reverse image search links across the major engines. */
    fun reverseImage(url: String): Result<String> = runCatching {
        val e = URLEncoder.encode(url, "UTF-8")
        "Reverse image search:\n" +
            "Google Lens: https://lens.google.com/uploadbyurl?url=$e\n" +
            "Yandex: https://yandex.com/images/search?rpt=imageview&url=$e\n" +
            "Bing: https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:$e\n" +
            "TinEye: https://tineye.com/search?url=$e"
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

    private fun getBody(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", ua).build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
