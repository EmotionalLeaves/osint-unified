package com.osint.unified.domain.executor

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.osint.unified.data.AppSettings
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Tool
import com.osint.unified.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Executes a single tool against an input and returns its raw output text.
 * Raw output is intentionally preserved verbatim so nothing is lost before
 * parsing or export.
 */
interface ToolExecutor {
    suspend fun execute(tool: Tool, input: String): Result<String>
}

/**
 * Runs CLOUD tools via the deployed backend: POST {baseUrl}/{toolId} with a
 * JSON body {"input": "..."} and returns the response body text.
 */
class CloudToolExecutor(
    private val settings: AppSettings,
    private val client: OkHttpClient = Http.client
) : ToolExecutor {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun execute(tool: Tool, input: String): Result<String> =
        withContext(Dispatchers.IO) {
            val base = settings.backendBaseUrl.trimEnd('/')
            if (base.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "No backend URL set. Open settings (gear icon) and enter your deployed backend URL."
                    )
                )
            }
            try {
                val payload = buildJsonObject { put("input", input) }.toString()
                val request = Request.Builder()
                    .url("$base/${tool.id}")
                    .post(payload.toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(text)
                    else Result.failure(IOException("HTTP ${resp.code}: ${text.take(300)}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

/**
 * Runs LOCAL tools shipped as native libraries. On Android 10+ you cannot exec
 * a binary from app-writable storage (W^X), but files in the app's
 * nativeLibraryDir ARE executable. The convention: ship tool <id> as
 * "lib<id>.so" in jniLibs/arm64-v8a. Until a binary is bundled, this returns a
 * clear, honest error rather than pretending it ran.
 */
class LocalToolExecutor(
    private val nativeLibraryDir: String,
    private val workDir: String
) : ToolExecutor {

    override suspend fun execute(tool: Tool, input: String): Result<String> =
        withContext(Dispatchers.IO) {
            val binary = File(nativeLibraryDir, "lib${tool.id}.so")
            if (!binary.exists()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "${tool.name} is a local tool but its binary isn't bundled in this build. " +
                            "It ships as jniLibs/arm64-v8a/lib${tool.id}.so once added to the CI build step."
                    )
                )
            }
            try {
                val command = buildCommand(tool, input, binary.absolutePath)
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .apply {
                        directory(File(workDir))
                        // Tools expect a writable HOME/TMP for config + caches.
                        environment()["HOME"] = workDir
                        environment()["TMPDIR"] = workDir
                    }
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(tool.timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    Result.failure(IOException("${tool.name} timed out after ${tool.timeoutMs} ms"))
                } else {
                    Result.success(output)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Builds the command line. When a workflow passes a multi-line host list
     * (e.g. subfinder output into httpx), it's written to a file and fed via
     * `-l` instead of `-u`.
     */
    private fun buildCommand(tool: Tool, input: String, binPath: String): List<String> {
        val cmd = mutableListOf(binPath)
        if (tool.id == "httpx" && input.contains('\n')) {
            val listFile = File(workDir, "httpx_targets.txt").apply { writeText(input) }
            cmd += listOf("-l", listFile.absolutePath, "-json", "-silent", "-title", "-tech-detect", "-status-code")
        } else {
            cmd += argsFor(tool, input)
        }
        return cmd
    }

    private fun argsFor(tool: Tool, input: String): List<String> = when (tool.id) {
        "sherlock" -> listOf(input, "--print-found", "--no-color")
        "maigret" -> listOf(input, "--json", "simple")
        "holehe" -> listOf(input, "--only-used")
        "phoneinfoga" -> listOf("scan", "-n", input)
        "subfinder" -> listOf("-d", input, "-silent", "-json")
        "amass" -> listOf("enum", "-passive", "-d", input, "-json", "/dev/stdout")
        "httpx" -> listOf("-u", input, "-json", "-silent", "-title", "-tech-detect", "-status-code")
        "exiftool" -> listOf("-j", "-a", "-u", "-g1", input)
        else -> listOf(input)
    }
}

/**
 * Runs DIRECT tools: public APIs the app calls itself, with no backend. Today
 * that is Overpass (OpenStreetMap infrastructure near a coordinate).
 */
class DirectToolExecutor(
    private val appContext: android.content.Context,
    private val usernameChecker: UsernameChecker,
    private val exifReader: com.osint.unified.data.ExifReader,
    private val apis: OsintApis = OsintApis(),
    private val client: OkHttpClient = Http.client
) : ToolExecutor {

    private val formUrlEncoded = "application/x-www-form-urlencoded".toMediaType()

    override suspend fun execute(tool: Tool, input: String): Result<String> =
        withContext(Dispatchers.IO) {
            when (tool.id) {
                "overpass_turbo" -> overpass(input)
                "sherlock", "maigret", "blackbird" -> runCatching { usernameChecker.check(input) }
                "emailaccounts" -> runCatching {
                    usernameChecker.check(input.substringBefore('@').substringBefore('+').trim())
                }
                "phoneinfoga" -> phoneInfo(input)
                "webcrawl" -> apis.webcrawl(input)
                "archive" -> apis.archive(input)
                "crtsh" -> apis.crtsh(input)
                "dns" -> apis.dns(input)
                "ipinfo" -> apis.ipinfo(input)
                "gravatar" -> apis.gravatar(input)
                "wayback" -> apis.wayback(input)
                "revgeo" -> apis.revgeo(input)
                "shodan" -> apis.shodan(input)
                "dorks" -> apis.dorks(input)
                "framework" -> framework(input)
                "rdap" -> apis.rdap(input)
                "bgpview" -> apis.bgpview(input)
                "hudsonrock" -> apis.hudsonRock(input)
                "urlscan" -> apis.urlscan(input)
                "websearch" -> apis.websearch(input)
                "github" -> apis.githubUser(input)
                "geocode" -> apis.geocode(input)
                "wikinearby" -> apis.wikiNearby(input)
                "gdelt" -> apis.gdeltNews(input)
                "reverseimage" -> apis.reverseImage(input)
                "exif" -> runCatching { exifReader.fromUrl(input) }
                else -> Result.failure(IllegalStateException("${tool.name} has no direct implementation."))
            }
        }

    /** Surfaces the matching OSINT Framework category's resources for this
     *  observable, so the whole framework is a connector inside investigations. */
    private fun framework(input: String): Result<String> = runCatching {
        val v = input.trim()
        val isEmail = v.contains('@') && !v.contains(' ')
        val isPhone = Regex("""^\+?[\d][\d\s\-().]{6,}$""").matches(v)
        val isIp = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""").matches(v)
        val isCoord = Regex("""^-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?$""").matches(v)
        val isImage = Regex(""".*\.(?:jpe?g|png|gif|webp|bmp|tiff?)(?:\?.*)?$""", RegexOption.IGNORE_CASE).matches(v)
        val isUrl = v.startsWith("http", true)
        val isDomain = !isEmail && !isIp && v.contains('.') && !v.contains(' ')
        val isName = v.contains(' ')
        val categories = when {
            isEmail -> listOf("Email Address", "Breach")
            isPhone -> listOf("Telephone Numbers")
            isIp -> listOf("IP Address", "Domain Name")
            isCoord -> listOf("Geolocation", "Maps", "Transport")
            isImage -> listOf("Images", "Videos", "Metadata")
            isUrl -> listOf("Archives", "Domain Name", "Malicious")
            isDomain -> listOf("Domain Name", "IP Address", "Archives")
            isName -> listOf("People Search", "Social Networks", "Search Engines", "Public Records", "Dating")
            else -> listOf("Username", "Social Networks")
        }
        val leaves = com.osint.unified.data.OsintFramework.categoryLeaves(appContext, categories)
        if (leaves.isEmpty()) "No OSINT Framework resources matched."
        else "${leaves.size} OSINT Framework resources:\n" +
            leaves.joinToString("\n") { (name, url) -> "$name: $url" }
    }

    private fun phoneInfo(input: String): Result<String> = try {
        val util = PhoneNumberUtil.getInstance()
        val number = util.parse(input, null)
        val region = util.getRegionCodeForNumber(number) ?: "?"
        val intl = util.format(number, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        val countryName = java.util.Locale("", region).displayCountry.ifBlank { region }
        val sb = StringBuilder()
        sb.append("Valid: ${util.isValidNumber(number)}\n")
        sb.append("Country: $countryName ($region)\n")
        sb.append("Country code: +${number.countryCode}\n")
        sb.append("Line type: ${util.getNumberType(number)}\n")
        sb.append("International: $intl\n")
        sb.append("National: ${util.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)}\n")
        val enc = java.net.URLEncoder.encode("\"$intl\"", "UTF-8")
        sb.append("\nSearch dorks:\n")
        sb.append("https://www.google.com/search?q=$enc\n")
        sb.append("https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}\n")
        Result.success(sb.toString())
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("Could not parse number (use E.164, e.g. +14155551234): ${e.message}"))
    }

    private fun overpass(input: String): Result<String> {
        val parts = input.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return Result.failure(IllegalArgumentException("Expected coordinates as 'lat, lon'."))
        }
        val lat = parts[0]!!
        val lon = parts[1]!!
        val query = "[out:json][timeout:25];(node(around:500,$lat,$lon)[amenity];node(around:500,$lat,$lon)[man_made];);out body 60;"
        return try {
            val body = ("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toRequestBody(formUrlEncoded)
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "OSINT-Unified/1.0 (research)")
                .header("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(text)
                else Result.failure(IOException("HTTP ${resp.code}: ${text.take(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** Picks the right executor for a tool based on its [ExecutionMode]. */
class ExecutorFactory(
    private val cloud: ToolExecutor,
    private val local: ToolExecutor,
    private val direct: ToolExecutor
) {
    fun forTool(tool: Tool): ToolExecutor =
        when (tool.executionMode) {
            ExecutionMode.CLOUD -> cloud
            ExecutionMode.LOCAL -> local
            ExecutionMode.DIRECT -> direct
        }
}
