package com.osint.unified.domain.executor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * On-device username enumeration — a native stand-in for Sherlock/Maigret that
 * needs no Python. Uses the community WhatsMyName ruleset: for each site,
 * request the profile URL with the username substituted and treat a match
 * (expected HTTP status + the expected marker string) as "account exists".
 * Runs with bounded concurrency and an overall time cap.
 */
class UsernameChecker(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun check(username: String): String = withContext(Dispatchers.IO) {
        val sites = loadSites()
        if (sites.isEmpty()) return@withContext "Could not load the site ruleset (check network)."

        val found = Collections.synchronizedList(mutableListOf<String>())
        val semaphore = Semaphore(64)

        val completed = withTimeoutOrNull(45_000) {
            coroutineScope {
                sites.map { site ->
                    async { semaphore.withPermit { checkSite(site, username)?.let { found.add(it) } } }
                }.awaitAll()
            }
            true
        } != null

        val hits = found.sorted()
        val header = if (hits.isEmpty()) {
            "No accounts found for \"$username\" across ${sites.size} sites."
        } else {
            "Found ${hits.size} of ${sites.size} sites" + (if (!completed) " (partial — timed out)" else "") + ":"
        }
        if (hits.isEmpty()) header else header + "\n" + hits.joinToString("\n")
    }

    private fun checkSite(site: WmnSite, username: String): String? {
        if (site.uri_check.isBlank() || site.e_code == null) return null
        val url = site.uri_check.replace("{account}", username)
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) OSINT-Unified")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code != site.e_code) return null
                val marker = site.e_string
                if (marker.isNullOrEmpty()) {
                    "${site.name} — $url"
                } else {
                    val body = resp.body?.string().orEmpty()
                    if (body.contains(marker)) "${site.name} — $url" else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadSites(): List<WmnSite> {
        val cache = File(appContext.cacheDir, "wmn-data.json")
        val text = if (cache.exists() && cache.length() > 1000) {
            cache.readText()
        } else {
            val downloaded = download() ?: return emptyList()
            runCatching { cache.writeText(downloaded) }
            downloaded
        }
        return runCatching { json.decodeFromString<WmnRoot>(text).sites }.getOrDefault(emptyList())
    }

    private fun download(): String? = try {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    @Serializable
    private data class WmnRoot(val sites: List<WmnSite> = emptyList())

    @Serializable
    private data class WmnSite(
        val name: String = "",
        val uri_check: String = "",
        val e_code: Int? = null,
        val e_string: String? = null,
        val cat: String = ""
    )
}
